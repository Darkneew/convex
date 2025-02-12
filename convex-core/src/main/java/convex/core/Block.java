package convex.core;

import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.List;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.ARecord;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.SignedData;
import convex.core.data.Tag;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.lang.impl.RecordFormat;
import convex.core.transactions.ATransaction;
import convex.core.util.Utils;

/**
 * A Block contains an ordered collection of signed transactions that may be applied 
 * collectively as part of a state update.
 * 
 * Blocks represent the units of novelty in the consensus system: a future state is 
 * 100% deterministic given the previous state and the Block to be applied.
 * 
 * "Man, the living creature, the creating individual, is always more important
 * than any established style or system." - Bruce Lee
 *
 */
public final class Block extends ARecord {
	private final long timestamp;
	private final AVector<SignedData<ATransaction>> transactions;

	private static final Keyword[] BLOCK_KEYS = new Keyword[] { Keywords.TIMESTAMP, Keywords.TRANSACTIONS};
	private static final RecordFormat FORMAT = RecordFormat.of(BLOCK_KEYS);

	/**
	 * Comparator to sort blocks by timestamp
	 */
	static final Comparator<SignedData<Block>> TIMESTAMP_COMPARATOR = new Comparator<>() {
		@Override
		public int compare(SignedData<Block> a, SignedData<Block> b) {
			int sig = Long.compare(a.getValue().getTimeStamp(), b.getValue().getTimeStamp());
			return sig;
		}
	};

	private Block(long timestamp, AVector<SignedData<ATransaction>> transactions) {
		super(FORMAT);
		this.timestamp = timestamp;
		this.transactions = transactions;
	}

	@Override
	public ACell get(ACell k) {
		if (Keywords.TIMESTAMP.equals(k)) return CVMLong.create(timestamp);
		if (Keywords.TRANSACTIONS.equals(k)) return transactions;
		return null;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected Block updateAll(ACell[] newVals) {
		long newTimestamp = RT.ensureLong(newVals[0]).longValue();		
		AVector<SignedData<ATransaction>> newTransactions = (AVector<SignedData<ATransaction>>) newVals[1];
		if ((this.transactions == newTransactions) && (this.timestamp == newTimestamp) ) {
			return this;
		}
		return new Block(newTimestamp, newTransactions);
	}

	/**
	 * Gets the timestamp of this block
	 * 
	 * @return Timestamp, as a long value
	 */
	public long getTimeStamp() {
		return timestamp;
	}

	/**
	 * Creates a block with the given timestamp and transactions
	 * 
	 * @param timestamp Timestamp for the newly created Block.
	 * @param transactions A java.util.List instance containing the required transactions
	 * @return A new Block containing the specified signed transactions
	 */
	public static Block create(long timestamp, List<SignedData<ATransaction>> transactions) {
		return new Block(timestamp, Vectors.create(transactions));
	}

	/**
	 * Creates a block with the given transactions.
	 * 
	 * @param timestamp Timestamp of block creation, according to Peer
	 * @param transactions Vector of transactions to include in Block
	 * 
	 * @return A new Block containing the specified signed transactions
	 */
	public static Block create(long timestamp, AVector<SignedData<ATransaction>> transactions) {
		return new Block(timestamp, transactions);
	}

	/**
	 * Creates a block with the given transactions.
	 * 
	 * @param timestamp Timestamp of block creation, according to Peer
	 * @param transactions Array of transactions to include in Block
	 * @return New Block
	 */
	@SafeVarargs
	public static Block of(long timestamp, SignedData<ATransaction>... transactions) {
		return new Block(timestamp, Vectors.of((Object[])transactions));
	}

	/**
	 * Gets the length of this block in number of transactions
	 * 
	 * @return Number of transactions on this block
	 */
	public int length() {
		return Utils.checkedInt(transactions.count());
	}
	
	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=getTag();
		// generic record writeRaw, handles all fields in declared order
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Utils.writeLong(bs,pos, timestamp);
		pos = transactions.encode(bs,pos);
		return pos;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 10+transactions.estimatedEncodingSize()+AccountKey.LENGTH;
	}

	/**
	 * Reads a Block from the given bytebuffer, assuming tag is already read
	 * 
	 * @param bb ByteBuffer containing Block representation
	 * @return A Block
	 * @throws BadFormatException if a Block could noy be read.
	 */
	public static Block read(ByteBuffer bb) throws BadFormatException {
		long timestamp = Format.readLong(bb);
		try {
			AVector<SignedData<ATransaction>> transactions = Format.read(bb);
			if (transactions==null) throw new BadFormatException("Null transactions");
			
			return Block.create(timestamp, transactions);
		} catch (ClassCastException e) {
			throw new BadFormatException("Error reading Block format", e);
		}
	}

	/**
	 * Get the vector of transactions in this Block
	 * @return Vector of transactions
	 */
	public AVector<SignedData<ATransaction>> getTransactions() {
		return transactions;
	}

	@Override
	public boolean isCanonical() {
		if (!transactions.isCanonical()) return false;
		return true;
	}

	@Override
	public byte getTag() {
		return Tag.BLOCK;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		transactions.validateCell();
	}
	
	@Override 
	public boolean equals(AMap<Keyword,ACell> a) {
		if (!(a instanceof Block)) return false;
		return equals((Block)a);
	}
	
	/**
	 * Tests if this Block is equal to another
	 * @param a PeerStatus to compare with
	 * @return true if equal, false otherwise
	 */
	public boolean equals(Block a) {
		if (a == null) return false;
		if (timestamp!=a.timestamp) return false;
		
		Hash h=this.cachedHash();
		if (h!=null) {
			Hash ha=a.cachedHash();
			if (ha!=null) return Utils.equals(h, ha);
		}
		
		if (!(Utils.equals(transactions, a.transactions))) return false;
		return true;
	}
	
}
