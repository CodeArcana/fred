package freenet.node;

import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.node.OpennetManager.ConnectionType;
import freenet.support.SimpleFieldSet;

public class OpennetPeerNode extends PeerNode {

	final OpennetManager opennet;
	private long timeLastSuccess;
	// Not persisted across restart, since after restart grace periods don't apply anyway (except disconnection, which is really separate anyway).
	private ConnectionType opennetNodeAddedReason;
	
	public OpennetPeerNode(SimpleFieldSet fs, Node node2, NodeCrypto crypto, OpennetManager opennet, PeerManager peers, boolean fromLocal, OutgoingPacketMangler mangler) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
		super(fs, node2, crypto, peers, fromLocal, false, mangler, true);

		if (fromLocal) {
			SimpleFieldSet metadata = fs.subset("metadata");
			timeLastSuccess = metadata.getLong("timeLastSuccess", 0);
		}
		
		this.opennet = opennet;
	}

	@Override
	public PeerNodeStatus getStatus(boolean noHeavy) {
		return new OpennetPeerNodeStatus(this, noHeavy);
	}

	@Override
	public boolean isRoutingCompatible() {
		if(!node.isOpennetEnabled()) return false;
		return super.isRoutingCompatible();
	}

	@Override
	public boolean isDarknet() {
		return false;
	}

	@Override
	public boolean isOpennet() {
		return true;
	}

	@Override
	public boolean isSeed() {
		return false;
	}

	enum NOT_DROP_REASON {
		DROPPABLE,
		TOO_NEW_PEER,
		TOO_LOW_UPTIME,
		RECONNECT_GRACE_PERIOD
	}
	
	public boolean isDroppable(boolean ignoreDisconnect) {
		return isDroppableWithReason(ignoreDisconnect) == NOT_DROP_REASON.DROPPABLE;
	}
		
	/** Is the peer droppable? 
	 * SIDE EFFECT: If we are now outside the grace period, we reset peerAddedTime and opennetPeerAddedReason. */ 
	public NOT_DROP_REASON isDroppableWithReason(boolean ignoreDisconnect) {
		long now = System.currentTimeMillis();
		int status = getPeerNodeStatus();
		long age = now - getPeerAddedTime();
		if(age < OpennetManager.DROP_MIN_AGE) {
			if(status == PeerManager.PEER_NODE_STATUS_NEVER_CONNECTED) {
				// New peer, never connected.
				// Allow it 1 minute to connect.
				if(age < OpennetManager.DROP_MIN_AGE_DISCONNECTED)
					return NOT_DROP_REASON.TOO_NEW_PEER;
			} else if(status != PeerManager.PEER_NODE_STATUS_DISCONNECTED) {
				// Based on the time added, *not* the last connected time.
				// This prevents various dubious ways of staying connected while not delivering anything useful.
				return NOT_DROP_REASON.TOO_NEW_PEER; // New node
			}
		} else {
			synchronized(this) {
				peerAddedTime = 0;
				opennetNodeAddedReason = null;
			}
		}
		if(now - node.usm.getStartedTime() < OpennetManager.DROP_STARTUP_DELAY)
			return NOT_DROP_REASON.TOO_LOW_UPTIME; // Give them time to connect after we startup
		if(!ignoreDisconnect) {
		synchronized(this) {
			// This only applies after it has connected, and only if !ignoreDisconnect.
			// Hence only DISCONNECTED and not NEVER CONNECTED.
			if((status == PeerManager.PEER_NODE_STATUS_DISCONNECTED) && (!super.neverConnected()) && 
					now - timeLastDisconnect < OpennetManager.DROP_DISCONNECT_DELAY &&
					now - timePrevDisconnect > OpennetManager.DROP_DISCONNECT_DELAY_COOLDOWN) {
				// Grace period for node restarting
				return NOT_DROP_REASON.RECONNECT_GRACE_PERIOD;
			}
		}
		}
		return NOT_DROP_REASON.DROPPABLE;
	}
	
	@Override
	public void onSuccess(boolean insert, boolean ssk) {
		if(insert || ssk) return;
		timeLastSuccess = System.currentTimeMillis();
		opennet.onSuccess(this);
	}

	@Override
	public void onRemove() {
		opennet.onRemove(this);
		super.onRemove();
	}
	
	@Override
	public synchronized SimpleFieldSet exportMetadataFieldSet() {
		SimpleFieldSet fs = super.exportMetadataFieldSet();
		fs.put("timeLastSuccess", timeLastSuccess);
		return fs;
	}

	public final long timeLastSuccess() {
		return timeLastSuccess;
	}
	
	/**
	 * Is the SimpleFieldSet a valid noderef?
	 */
	public static boolean validateRef(SimpleFieldSet ref) {
		if(!ref.getBoolean("opennet", false)) return false;
		return true;
	}

	@Override
	public boolean isRealConnection() {
		return true;
	}

	@Override
	public boolean recordStatus() {
		return true;
	}

	@Override
	protected boolean generateIdentityFromPubkey() {
		return false;
	}
 
	@Override
	public boolean equals(Object o) {
		if(o == this) return true;
		// Only equal to seednode of its own type.
		if(o instanceof OpennetPeerNode) {
			return super.equals(o);
		} else return false;
	}
	
	@Override
	public final boolean shouldDisconnectAndRemoveNow() {
		// Allow announced peers 15 minutes to download the auto-update.
		if(isConnected() && isUnroutableOlderVersion() && System.currentTimeMillis() - timeLastConnectionCompleted() > 60*60*1000)
			return true;
		return false;
	}

	@Override
	protected void onConnect() {
		super.onConnect();
		opennet.crypto.socket.getAddressTracker().setPresumedGuiltyAt(System.currentTimeMillis()+60*60*1000);
	}
	
	private boolean wasDropped;

	synchronized void setWasDropped() {
		wasDropped = true;
	}
	
	synchronized boolean wasDropped() {
		return wasDropped;
	}
	
	synchronized boolean grabWasDropped() {
		boolean ret = wasDropped;
		wasDropped = false;
		return ret;
	}
	
	public synchronized void setAddedReason(ConnectionType connectionType) {
		opennetNodeAddedReason = connectionType;
	}
	
	public synchronized ConnectionType getAddedReason() {
		return opennetNodeAddedReason;
	}

	@Override
	/** Opennet nodes need to know when a peer node was added. 
	 * We do NOT clear it on connect, because we use it for determining whether we are in the initial grace period.
	 * However we will reset it after the grace period expires, in isDroppableWithReason(). */
	protected void maybeClearPeerAddedTimeOnConnect() {
		// Guarantee that it gets cleared.
		node.getTicker().queueTimedJob(new FastRunnable() {

			public void run() {
				isDroppableWithReason(false);
			}
			
		}, OpennetManager.DROP_MIN_AGE+1);
	}

	@Override
	/* Opennet peers do not export the peer added time. It is only relevant for the grace period anyway. */ 
	protected boolean shouldExportPeerAddedTime() {
		return false;
	}

	@Override
	protected void maybeClearPeerAddedTimeOnRestart(long now) {
		// Do nothing.
	}
}
