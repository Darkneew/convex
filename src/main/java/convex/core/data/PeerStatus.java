package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.impl.RecordFormat;

/**
 * Class describing the on-chain state of a Peer declared on the network.
 * 
 * State includes: - Stake placed by this Peer - A host address for peer
 * connections / client requests
 *
 */
public class PeerStatus extends ARecord {
	private static final Keyword[] PEER_KEYS = new Keyword[] { Keywords.STAKE, Keywords.STAKES,Keywords.DELEGATED_STAKE,
			Keywords.URL};

	private static final RecordFormat FORMAT = RecordFormat.of(PEER_KEYS);
	
	private final long stake;
	private final long delegatedStake;

	private final ABlobMap<Address, Long> stakes;

	private final AString hostAddress;

	private PeerStatus(long stake, ABlobMap<Address, Long> stakes, long delegatedStake, AString host) {
		super(FORMAT);
		this.stake = stake;
		this.delegatedStake = delegatedStake;
		this.hostAddress = host;
		this.stakes = stakes;
	}

	public static PeerStatus create(long stake) {
		return create(stake, null);
	}

	public static PeerStatus create(long stake, AString hostString) {
		return new PeerStatus(stake, BlobMaps.empty(), 0L, hostString);
	}
	/**
	 * Gets the stake of this peer
	 * 
	 * @return Total stake, including own stake + delegated stake
	 */
	public long getTotalStake() {
		return stake + delegatedStake;
	}

	/**
	 * Gets the self-owned stake of this peer
	 * 
	 * @return Own stake, excluding delegated stake
	 */
	public long getOwnStake() {
		return stake;
	}

	/**
	 * Gets the delegated stake of this peer
	 * 
	 * @return Total of delegated stake
	 */
	public long getDelegatedStake() {
		return delegatedStake;
	}

	/**
	 * Gets the String representation of the host address, or null if not specified
	 * 
	 * @return Host String
	 */
	public AString getHostString() {
		if (hostAddress == null) return null;
		return hostAddress;
	}

	@Override
	public int write(byte[] bs, int pos) {
		bs[pos++]=Tag.PEER_STATUS;
		return writeRaw(bs,pos);
	}

	@Override
	public int writeRaw(byte[] bs, int pos) {
		pos = Format.writeVLCLong(bs,pos, stake);
		pos = Format.write(bs,pos, stakes);
		pos = Format.writeVLCLong(bs,pos, delegatedStake);
		pos = Format.write(bs,pos, getHostString());
		return pos;
	}

	public static PeerStatus read(ByteBuffer data) throws BadFormatException {
		long stake = Format.readVLCLong(data);
		ABlobMap<Address, Long> stakes = Format.read(data);
		long delegatedStake = Format.readVLCLong(data);
		
		AString hostString = Format.read(data);
		
		return new PeerStatus(stake,stakes,delegatedStake,hostString);
	}

	@Override
	public int estimatedEncodingSize() {
		return 100;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	/**
	 * Gets the delegated stake on this peer for the given delegator.
	 * 
	 * Returns 0 if the delegator has no stake.
	 * 
	 * @param delegator Address of delegator
	 * @return Value of delegated stake
	 */
	public long getDelegatedStake(Address delegator) {
		Long a = stakes.get(delegator);
		if (a == null) return 0;
		return a;
	}

	/**
	 * Sets the delegated stake on this peer for the given delegator.
	 * 
	 * A value of 0 will remove the delegator's stake entirely
	 * 
	 * @param delegator Address of delegator
	 * @return Value of delegated stake
	 */
	public PeerStatus withDelegatedStake(Address delegator, long newStake) {
		long oldStake = getDelegatedStake(delegator);
		if (oldStake == newStake) return this;

		// compute adjustment to total delegated stake
		long newDelegatedStake = delegatedStake + newStake - oldStake;

		ABlobMap<Address, Long> newStakes = (newStake == 0L) ? stakes.dissoc(delegator)
				: stakes.assoc(delegator, newStake);
		return new PeerStatus(stake, newStakes, newDelegatedStake, hostAddress);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// TODO: Nothing?
	}

	@Override
	protected String ednTag() {
		return "#peer-status";
	}

	@SuppressWarnings("unchecked")
	@Override
	public <V> V get(Keyword key) {
		if (Keywords.STAKE.equals(key)) return (V) (Long)stake;
		if (Keywords.STAKES.equals(key)) return (V) stakes;
		if (Keywords.DELEGATED_STAKE.equals(key)) return (V) (Long)delegatedStake;
		if (Keywords.URL.equals(key)) return (V) hostAddress;
		
		return null;
	}

	@Override
	public byte getRecordTag() {
		return Tag.PEER_STATUS;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected PeerStatus updateAll(Object[] newVals) {
		long newStake = (Long) newVals[0];
		ABlobMap<Address, Long> newStakes = (ABlobMap<Address, Long>) newVals[1];
		Long newDelStake = (Long) newVals[2];
		AString newHostAddress = (AString) newVals[3];
		
		if ((this.stake==newStake)&&(this.stakes==newStakes)
				&&(this.hostAddress==newHostAddress)&&(this.delegatedStake==newDelStake)) {
			return this;
		}
		return new PeerStatus(newStake, newStakes, newDelStake, newHostAddress);
	}

	protected static long computeDelegatedStake(ABlobMap<Address, Long> stakes) {
		long ds = stakes.reduceValues((acc, e)->acc+e, 0L);
		return ds;
	}

}
