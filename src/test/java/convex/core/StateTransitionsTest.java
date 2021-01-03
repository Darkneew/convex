package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.Ed25519KeyPair;
import convex.core.data.ABlob;
import convex.core.data.AVector;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.BlobMap;
import convex.core.data.BlobMaps;
import convex.core.data.SignedData;
import convex.core.exceptions.BadSignatureException;
import convex.core.lang.Juice;
import convex.core.lang.Reader;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.transactions.Transfer;
import convex.core.util.Utils;

/**
 * Tests for State transition scenarios
 */
public class StateTransitionsTest {

	final AKeyPair KEYPAIR_A = Ed25519KeyPair.createSeeded(1001);
	final AKeyPair KEYPAIR_B = Ed25519KeyPair.createSeeded(1002);
	final AKeyPair KEYPAIR_C = Ed25519KeyPair.createSeeded(1003);
	final AKeyPair KEYPAIR_NIKI = Ed25519KeyPair.createSeeded(1004);
	final AKeyPair KEYPAIR_ROBB = Ed25519KeyPair.createSeeded(1005);

	final Address ADDRESS_A = KEYPAIR_A.getAddress();
	final Address ADDRESS_B = KEYPAIR_B.getAddress();
	final Address ADDRESS_C = KEYPAIR_C.getAddress();
	final Address ADDRESS_NIKI = KEYPAIR_NIKI.getAddress();
	final Address ADDRESS_ROBB = KEYPAIR_ROBB.getAddress();
	
	@Test
	public void testAccountTransfers() throws BadSignatureException {
		BlobMap<Address, AccountStatus> accounts = BlobMaps.of(ADDRESS_A, AccountStatus.create(10000),
				ADDRESS_B, AccountStatus.create(1000L), ADDRESS_ROBB,
				AccountStatus.create(Constants.MAX_SUPPLY - 10000 - 1000)
		// No account for C yet
		);
		State s = State.EMPTY.withAccounts(accounts); // don't need any peers for these tests
		assertEquals(Constants.MAX_SUPPLY, s.computeTotalFunds());

		assertEquals(10000, s.getBalance(ADDRESS_A));
		assertEquals(1000, s.getBalance(ADDRESS_B));
		assertNull(s.getBalance(ADDRESS_C));

		long TCOST = Juice.TRANSFER * s.getJuicePrice();

		{ // transfer from existing to existing account A->B
			Transfer t1 = Transfer.create(1, ADDRESS_B, 50);
			SignedData<ATransaction> st = KEYPAIR_A.signData(t1);
			long nowTS = Utils.getCurrentTimestamp();
			Block b = Block.of(nowTS, st);
			BlockResult br = s.applyBlock(b);
			AVector<Result> results = br.getResults();
			assertEquals(1, results.count());
			assertNull(br.getErrorCode(0)); // should be null for successful transfer transaction
			State s2 = br.getState();
			assertEquals(9950 - TCOST, s2.getBalance(ADDRESS_A));
			assertEquals(1050, s2.getBalance(ADDRESS_B));
			assertEquals(nowTS, s2.getTimeStamp());
		}

		{ // transfer from existing to new account A -> C
			Transfer t1 = Transfer.create(1, ADDRESS_C, 50);
			SignedData<ATransaction> st = KEYPAIR_A.signData(t1);
			Block b = Block.of(System.currentTimeMillis(), st);
			State s2 = s.applyBlock(b).getState();
			assertEquals(9950 - TCOST, s2.getBalance(ADDRESS_A));
			assertEquals(50, s2.getBalance(ADDRESS_C));
		}

		{ // two transfers in sequence, both from A -> C
			Transfer t1 = Transfer.create(1, ADDRESS_C, 150);
			SignedData<ATransaction> st1 = KEYPAIR_A.signData(t1);
			Transfer t2 = Transfer.create(2, ADDRESS_C, 150);
			SignedData<ATransaction> st2 = KEYPAIR_A.signData(t2);
			Block b = Block.of(System.currentTimeMillis(), st1, st2);

			BlockResult br = s.applyBlock(b);
			State s2 = br.getState();
			assertEquals(9700 - TCOST * 2, s2.getBalance(ADDRESS_A));
			assertEquals(1000, s2.getBalance(ADDRESS_B));
			assertEquals(300, s2.getBalance(ADDRESS_C));
		}

		{ // two transfers in sequence, 2 different accounts A B --> new account C
			Transfer t1 = Transfer.create(1, ADDRESS_C, 50);
			SignedData<ATransaction> st1 = KEYPAIR_A.signData(t1);
			Transfer t2 = Transfer.create(1, ADDRESS_C, 50);
			SignedData<ATransaction> st2 = KEYPAIR_B.signData(t2);
			Block b = Block.of(System.currentTimeMillis(), st1, st2);

			BlockResult br = s.applyBlock(b);
			State s2 = br.getState();
			assertEquals(9950 - TCOST, s2.getBalance(ADDRESS_A));
			assertEquals(950 - TCOST, s2.getBalance(ADDRESS_B));
			assertEquals(100, s2.getBalance(ADDRESS_C));

			AVector<Result> results = br.getResults();
			assertEquals(2, results.count());
			assertEquals(50L,br.getResult(0).getValue()); // null result for successful transfer
			assertEquals(Constants.MAX_SUPPLY, br.getState().computeTotalFunds());
		}

		{ // transfer with an incorrect sequence number
			Transfer t1 = Transfer.create(2, ADDRESS_C, 50);
			SignedData<ATransaction> st = KEYPAIR_A.signData(t1);
			Block b = Block.of(System.currentTimeMillis(), st);
			BlockResult br = s.applyBlock(b);
			AVector<Result> results = br.getResults();
			assertEquals(1, results.count());
			assertEquals(ErrorCodes.SEQUENCE, br.getResult(0).getErrorCode());
		}

		{ // transfer amount greater than current balance
			Transfer t1 = Transfer.create(1, ADDRESS_C, 50000);
			SignedData<ATransaction> st = KEYPAIR_A.signData(t1);
			Block b = Block.of(System.currentTimeMillis(), st);
			BlockResult br = s.applyBlock(b);
			assertEquals(ErrorCodes.FUNDS, br.getResult(0).getErrorCode());

			State newState = br.getState();
			assertEquals(Constants.MAX_SUPPLY, newState.computeTotalFunds());
		}

		{ // transfer from a non-existent address
			Transfer t1 = Transfer.create(1, ADDRESS_B, 50);
			SignedData<ATransaction> st = KEYPAIR_C.signData(t1);
			Block b = Block.of(System.currentTimeMillis(), st);
			assertEquals(ErrorCodes.NOBODY, s.applyBlock(b).getResult(0).getErrorCode());

		}

		{ // sending money to NIKI, a new account
			// System.out.println(ADDRESS_NIKI);
			// System.out.println("Niki has "+s.getBalance(ADDRESS_NIKI).getValue());

			long AMT = 500;
			// System.out.println("Tansferring "+AMT+" to Niki");

			Transfer t1 = Transfer.create(1, ADDRESS_NIKI, AMT);
			SignedData<ATransaction> st = KEYPAIR_A.signData(t1);
			Block b = Block.of(System.currentTimeMillis(), st);
			BlockResult br = s.applyBlock(b);
			// System.out.println("Transfer complete....");

			State newState = br.getState();
			assertEquals(AMT, newState.getBalance(ADDRESS_NIKI));
			// System.out.println("Niki has "+newState.getBalance(ADDRESS_NIKI).getValue());

		}

	}
	
	@Test
	public void testDeploys() throws BadSignatureException {
		State s = Init.STATE;
		ATransaction t1 = Invoke.create(1,Reader.read("(def my-lib-address (deploy-once (defn foo [x] x)))"));
		AKeyPair kp = convex.core.lang.TestState.HERO_PAIR;
		Block b1 = Block.of(s.getTimeStamp(), kp.signData(t1));
		BlockResult br=s.applyBlock(b1);
		assertFalse(br.isError(0));
		
		s = br.getState();
		
	}
	
	@Test
	public void testMemoryAccounting() throws BadSignatureException {
		State s = Init.STATE;
		AKeyPair kp = convex.core.lang.TestState.HERO_PAIR;
		
		long initialMem=s.getAccount(Init.HERO).getMemoryUsage();
		
		ATransaction t1 = Invoke.create(1,Reader.read("(def a 1)"));
		Block b1 = Block.of(s.getTimeStamp(), kp.signData(t1));
		s = s.applyBlock(b1).getState();
		
		assertTrue(initialMem<s.getAccount(Init.HERO).getMemoryUsage());
	}

	@Test
	public void testScheduleOps() throws BadSignatureException {
		State s = Init.STATE;
		Address TARGET = Address.dummy("2");
		String taddr=TARGET.toHexString();

		long INITIAL_TS = s.getTimeStamp();
		AKeyPair kp = convex.core.lang.TestState.HERO_PAIR;
		long BAL2 = s.getBalance(TARGET);

		ATransaction t1 = Invoke.create(1,
				Reader.read("(transfer \""+taddr+"\" 10000000)"));
		Block b1 = Block.of(s.getTimeStamp() + 100, kp.signData(t1));
		s = s.applyBlock(b1).getState();
		assertEquals(BAL2 + 10000000, s.getBalance(TARGET));
		assertEquals(INITIAL_TS + 100, s.getTimeStamp());
		
		// schedule 200ms later for 1s time
		ATransaction t2 = Invoke.create(2, Reader.read(
				"(schedule (+ *timestamp* 1000) (transfer \""+taddr+"\" 10000000))"));
		Block b2 = Block.of(s.getTimeStamp() + 200, kp.signData(t2));
		BlockResult br2 = s.applyBlock(b2);
		assertNull(br2.getErrorCode(0));
		s = br2.getState();
		BlobMap<ABlob, AVector<Object>> sched2 = s.getSchedule();
		assertEquals(1L, sched2.count());
		// no change to target balance yet
		assertEquals(BAL2 + 10000000, s.getBalance(TARGET));

		// advance 999ms
		ATransaction t3 = Invoke.create(3, Reader.read("1"));
		Block b3 = Block.of(s.getTimeStamp() + 999, kp.signData(t3));
		BlockResult br3 = s.applyBlock(b3);
		assertNull(br3.getErrorCode(0));
		s = br3.getState();
		// no change to target balance yet
		assertEquals(BAL2 + 10000000, s.getBalance(TARGET));

		// advance 1ms to trigger scheduled transfer
		ATransaction t4 = Invoke.create(4, Reader.read("1"));
		Block b4 = Block.of(s.getTimeStamp() + 1, kp.signData(t4));
		BlockResult br4 = s.applyBlock(b4);
		assertNull(br4.getErrorCode(0));
		s = br4.getState();
		// no change to target balance yet
		assertEquals(BAL2 + 20000000, s.getBalance(TARGET));

	}

}
