package convex.cli.peer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import convex.api.Convex;
import convex.api.Shutdown;
import convex.cli.Helpers;
import convex.cli.Main;
import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.core.data.AccountKey;
import convex.core.data.ACell;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.init.AInitConfig;
import convex.core.lang.Reader;
import convex.core.store.AStore;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.util.Utils;
import convex.core.Result;
import convex.peer.API;
import convex.peer.IServerEvent;
import convex.peer.Server;
import convex.peer.ServerEvent;
import convex.peer.ServerInformation;

import etch.EtchStore;


/**
*
* Convex PeerManager
*
*/

public class PeerManager implements IServerEvent {

	private static final Logger log = Logger.getLogger(PeerManager.class.getName());

	protected List<Server> peerServerList = new ArrayList<Server>();

	protected Session session = new Session();

	protected String sessionFilename;

	protected BlockingQueue<ServerEvent> serverEventQueue = new ArrayBlockingQueue<ServerEvent>(1024);


	private PeerManager(String sessionFilename) {
        this.sessionFilename = sessionFilename;
	}

	public static PeerManager create(String sessionFilename) {
        return new PeerManager(sessionFilename);
	}

	public void launchLocalPeers(int count, AInitConfig initConfig) {
		peerServerList = API.launchLocalPeers(count, initConfig, this);

		// we need to start doing the first invoke on a peer to start all the other
		// peers to connect and sync with the consensus.

		Server server = peerServerList.get(0);
		InetSocketAddress hostAddress = server.getHostAddress();

		Address peerAddress = initConfig.getUserAddress(0);
		try {
			Convex convex = Convex.connect(hostAddress, peerAddress, initConfig.getUserKeyPair(0));

			// send a 'do' to wake up the other peers
			ACell message = Reader.read("(do)");
			ATransaction transaction = Invoke.create(peerAddress,-1, message);
			Future<Result> future = convex.transact(transaction);
		} catch (IOException e) {
			log.severe("cannot connect to the first peer "+e);
		}
	}

    public void launchPeer(AKeyPair keyPair, Address peerAddress, String hostname, int port, AStore store) {
		Map<Keyword, Object> config = new HashMap<>();
		config.put(Keywords.PORT, port);
		config.put(Keywords.STORE, store);
		config.put(Keywords.KEYPAIR, keyPair);
		Server server = API.launchPeer(config, this);
		server.joinNetwork(keyPair, peerAddress, hostname);
		peerServerList.add(server);
	}

	/**
	 * Load in a session from a session file.
	 *
	 * @param sessionFilename Filename to load.
	 *
	 */
	protected void loadSession() {
		File sessionFile = new File(sessionFilename);
		try {
			session.load(sessionFile);
		} catch (IOException e) {
			log.severe("Cannot load the session control file");
		}
	}

	/**
	 * Add a peer to the session list of peers.
	 *
	 * @param peerServer Add the peerServer to the list of peers for this session.
	 *
	 */
	protected void addToSession(Server peerServer) {
		EtchStore store = (EtchStore) peerServer.getStore();

		session.addPeer(
			peerServer.getPeerKey().toHexString(),
			peerServer.getHostname(),
			store.getFileName()
		);
	}

	/**
	 * Add all peers started in this session to the session list.
	 *
	 */
	protected void addAllToSession() {
		for (Server peerServer: peerServerList) {
			addToSession(peerServer);
		}
	}

	/**
	 * Remove all peers added by this manager from the session list of peers.
	 *
	 */
	protected void removeAllFromSession() {
		for (Server peerServer: peerServerList) {
			session.removePeer(peerServer.getPeerKey().toHexString());
		}
	}

	/**
	 * Store the session details to file.
	 *
	 * @param sessionFilename Fileneame to save the session.
	 *
	 */
	protected void storeSession() {
		File sessionFile = new File(sessionFilename);
		try {
			Helpers.createPath(sessionFile);
			if (session.getPeerCount() > 0) {
				session.store(sessionFile);
			}
			else {
				sessionFile.delete();
			}
		} catch (IOException e) {
			log.severe("Cannot store the session control data");
		}
	}

	/**
	 * Once the manager has launched 1 or more peers. The manager now needs too loop and show any events generated by the peers
	 *
	 */
	public void showPeerEvents() {

		loadSession();
		addAllToSession();
		storeSession();

		/*
			Go through each started peer server connection and make sure
			that each peer is connected to the other peer.
		*/
		/*
		for (Server peerServer: peerServerList) {
			connectToPeers(peerServer, session.getPeerAddressList());
		}
		*/

		// shutdown hook to remove/update the session file
		convex.api.Shutdown.addHook(Shutdown.CLI,new Runnable() {
		    public void run() {
				// System.out.println("peers stopping");
				// remove session file
				loadSession();
				removeAllFromSession();
				storeSession();
		    }
		});

		Server firstServer = peerServerList.get(0);
		System.out.println("Starting network Id: "+ firstServer.getPeer().getNetworkID().toString());
		while (true) {
			try {
				ServerEvent event = serverEventQueue.take();
                ServerInformation information = event.getInformation();
				int index = getServerIndex(information.getPeerKey());
				if (index >=0) {
					String item = toServerInformationText(information);
					System.out.println(String.format("#%d: %s Msg: %s", index + 1, item, event.getReason()));
				}
			} catch (InterruptedException e) {
				System.out.println("Peer manager interrupted!");
				return;
			}
		}
	}

	protected String toServerInformationText(ServerInformation serverInformation) {
		String shortName = Utils.toFriendlyHexString(serverInformation.getPeerKey().toHexString()).replaceAll("^0x", "");
		String hostname = serverInformation.getHostname();
		String joined = "NJ";
		String synced = "NS";
		if (serverInformation.isJoined()) {
			joined = " J";
		}
		if (serverInformation.isSynced()) {
			synced = " S";
		}
		long blockCount = serverInformation.getBlockCount();
		int connectionCount = serverInformation.getConnectionCount();
		int trustedConnectionCount = serverInformation.getTrustedConnectionCount();
		long consensusPoint = serverInformation.getConsensusPoint();
		String item = String.format("Peer:%s URL: %s Status:%s %s Connections:%2d/%2d Level:%4d Block:%4d",
				shortName,
				hostname,
				joined,
				synced,
				connectionCount,
				trustedConnectionCount,
				consensusPoint,
				blockCount
		);

		return item;
	}

	protected int getServerIndex(AccountKey peerKey) {
		for (int index = 0; index < peerServerList.size(); index ++) {
			Server server = peerServerList.get(index);
			if (server.getPeer().getPeerKey().equals(peerKey)) {
				return index;
			}
		}
		return -1;
	}

	/**
	 * Implements for IServerEvent
	 *
	 */

	public void onServerChange(ServerEvent serverEvent) {
		// add in queue if space available
		serverEventQueue.offer(serverEvent);
	}
}
