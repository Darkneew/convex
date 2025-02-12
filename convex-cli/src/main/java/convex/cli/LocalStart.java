package convex.cli;

import java.lang.NumberFormatException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import convex.cli.peer.PeerManager;
import convex.core.crypto.AKeyPair;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/*
 * 		local start command
 *
 *		convex.local.start
 *
 */

@Command(name="start",
	mixinStandardHelpOptions=true,
	description="Starts a local convex test network.")
public class LocalStart implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(LocalStart.class);

	@ParentCommand
	private Local localParent;

	@Option(names={"--count"},
		defaultValue = "" + Constants.LOCAL_START_PEER_COUNT,
		description="Number of local peers to start. Default: ${DEFAULT-VALUE}")
	private int count;

	@Option(names={"--public-key"},
		defaultValue="",
		description="One or more hex string of the public key in the Keystore to use to run a peer.%n"
			+ "You only need to enter in the first distinct hex values of the public key.%n"
			+ "For example: 0xf0234 or f0234")
	private String[] keystorePublicKey;

    @Option(names={"--ports"},
		description="List of ports to assign to peers in the cluster. If not specified, will attempt to find available ports."
			+ "or a single --ports=8081,8082,8083 or --ports=8080-8090")
	private String[] ports;

    /**
     * Gets n public keys for local test cluster
     * @param n Number of public keys
     * @return List of distinct public keys
     */
    private List<AKeyPair> getPublicKeys(int n) {
    	HashSet<AKeyPair> keyPairList = new HashSet<AKeyPair>();
    	
    	Main mainParent = localParent.mainParent;
		// load in the list of public keys to use as peers
		if (keystorePublicKey.length > 0) {
			List<String> values = Helpers.splitArrayParameter(keystorePublicKey);
			
			for (int index = 0; index < values.size(); index ++) {
				String keyPrefix = values.get(index);

				AKeyPair keyPair = mainParent.loadKeyFromStore(keyPrefix);
				if (keyPair == null) throw new CLIError("Unable to find public key in store: "+keyPrefix);
				keyPairList.add(keyPair);
			}
		}
		int left=keyPairList.size()-n;
		if (left>0) {
			log.warn("Insufficient key pairs specified. Additional keypairs will be generated");
		
			List<AKeyPair> kp=mainParent.generateKeyPairs(left);
			keyPairList.addAll(kp);
			mainParent.saveKeyStore();
		}
		if (keyPairList.size()<n) {
			throw new CLIError("Unable to generate sufficient keypairs!");
		}
		
		return new ArrayList<AKeyPair>(keyPairList);
    }
    
	@Override
	public void run() {
		Main mainParent = localParent.mainParent;
		PeerManager peerManager = PeerManager.create(mainParent.getSessionFilename());

		List<AKeyPair> keyPairList = getPublicKeys(count);

		int peerPorts[] = null;
		if (ports != null) {
			try {
				peerPorts = mainParent.getPortList(ports, count);
			} catch (NumberFormatException e) {
				log.warn("cannot convert port number " + e);
				return;
			}
			if (peerPorts.length < count) {
				log.warn("Only {} ports specified for {} peers", peerPorts.length, count);
				return;
			}
		}
		log.info("Starting local network with "+count+" peer(s)");
		peerManager.launchLocalPeers(keyPairList, peerPorts);
		log.info("Local Peers launched");
		peerManager.showPeerEvents();
	}
}
