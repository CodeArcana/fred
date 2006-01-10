package freenet.client;

import freenet.client.events.ClientEventListener;
import freenet.keys.FreenetURI;
import freenet.node.RequestStarterClient;

public interface HighLevelSimpleClient {

	/**
	 * Set the maximum length of the fetched data.
	 */
	public void setMaxLength(long maxLength);
	
	/**
	 * Set the maximum length of any intermediate data, e.g. ZIP manifests.
	 */
	public void setMaxIntermediateLength(long maxIntermediateLength);

	/**
	 * Blocking fetch of a URI
	 * @throws FetchException If there is an error fetching the data
	 */
	public FetchResult fetch(FreenetURI uri) throws FetchException;

	/**
	 * Blocking insert.
	 * @throws InserterException If there is an error inserting the data
	 */
	public FreenetURI insert(InsertBlock insert, boolean getCHKOnly) throws InserterException;

	/**
	 * Blocking insert of a redirect.
	 */
	public FreenetURI insertRedirect(FreenetURI insertURI, FreenetURI target) throws InserterException;
	
	/**
	 * Add a ClientEventListener.
	 */
	public void addGlobalHook(ClientEventListener listener);
}
