package net.nexora.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreStoreTest {
    @TempDir Path dir;
    CoreStore store;
    UUID a=UUID.randomUUID(),b=UUID.randomUUID();
    @BeforeEach void open() throws Exception {
        store=new CoreStore(dir.resolve("test.db"));store.seen(a,"Alice",1);store.seen(b,"Bob",2);
    }
    @AfterEach void close() throws Exception {store.close();}
    @Test void newAccountsStartAtZero() throws Exception {assertEquals(0,store.balance(a));}
    @Test void rejoinDoesNotResetBalance() throws Exception {
        store.change(a,123,"add","CONSOLE");store.seen(a,"AliceNew",3);assertEquals(123,store.balance(a));assertEquals("AliceNew",store.name(a));
    }
    @Test void transferIsBalanced() throws Exception {
        store.change(a,100,"set","CONSOLE");store.transfer(a,b,40);assertEquals(60,store.balance(a));assertEquals(40,store.balance(b));
    }
    @Test void insufficientFundsRollBackBothSides() throws Exception {
        store.change(a,10,"set","CONSOLE");assertThrows(IllegalArgumentException.class,()->store.transfer(a,b,11));assertEquals(10,store.balance(a));assertEquals(0,store.balance(b));
    }
    @Test void overflowRollsBackBothSides() throws Exception {
        store.change(a,10,"set","CONSOLE");store.change(b,Long.MAX_VALUE,"set","CONSOLE");
        assertThrows(ArithmeticException.class,()->store.transfer(a,b,1));assertEquals(10,store.balance(a));assertEquals(Long.MAX_VALUE,store.balance(b));
    }
    @Test void negativeAndSelfTransfersRejected(){
        assertThrows(IllegalArgumentException.class,()->store.transfer(a,b,-1));assertThrows(IllegalArgumentException.class,()->store.transfer(a,a,1));assertThrows(IllegalArgumentException.class,()->store.transfer(a,b,0));
    }
    @Test void unknownAccountCannotBeCredited(){assertThrows(IllegalArgumentException.class,()->store.change(UUID.randomUUID(),1,"add","x"));}
    @Test void negativeBalancesRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,()->store.change(a,-1,"set","x"));assertThrows(IllegalArgumentException.class,()->store.change(a,1,"take","x"));assertEquals(0,store.balance(a));
    }
    @Test void pendingPurchaseDebitsOnceAndBlocksAnother() throws Exception {
        store.change(a,100,"set","x");store.reserve(a,"shop:test",20,"item",1000,0);
        assertThrows(IllegalArgumentException.class,()->store.reserve(a,"shop:test",20,"item",1001,0));assertEquals(80,store.balance(a));
    }
    @Test void refundIsExactlyOnce() throws Exception {
        store.change(a,100,"set","x");String id=store.reserve(a,"shop:test",20,"item",1000,0);
        store.resolve(id,false,"owner");assertEquals(100,store.balance(a));assertThrows(IllegalArgumentException.class,()->store.resolve(id,false,"owner"));
    }
    @Test void completionDoesNotRefund() throws Exception {
        store.change(a,30,"set","x");String id=store.reserve(a,"shop:test",20,"item",1000,0);store.resolve(id,true,"x");assertEquals(10,store.balance(a));assertTrue(store.pending(a).isEmpty());
    }
    @Test void cooldownSurvivesCompletion() throws Exception {
        String id=store.reserve(a,"kit:test",0,"item",1000,5000);store.resolve(id,true,"x");
        assertThrows(IllegalArgumentException.class,()->store.reserve(a,"kit:test",0,"item",5999,5000));assertNotNull(store.reserve(a,"kit:test",0,"item",6000,5000));
    }
    @Test void refundRestoresPriorCooldown() throws Exception {
        String id=store.reserve(a,"kit:test",0,"item",1000,5000);store.resolve(id,false,"x");assertEquals(0,store.ready(a,"kit:test"));
    }
    @Test void negativeOfferRejected(){assertThrows(IllegalArgumentException.class,()->store.reserve(a,"shop:test",-1,"item",0,0));}
    @Test void cooldownOverflowRollsBack() throws Exception {
        assertThrows(ArithmeticException.class,()->store.reserve(a,"kit:test",0,"item",Long.MAX_VALUE,1));assertTrue(store.pending(a).isEmpty());
    }
    @Test void backupIncludesCommittedData() throws Exception {
        store.change(a,55,"set","x");Path backup=dir.resolve("backup.db");store.backup(backup);
        try(CoreStore copy=new CoreStore(backup)){assertEquals(55,copy.balance(a));}
    }
    @Test void restartPreservesPendingBalanceAndCooldown() throws Exception {
        store.change(a,100,"set","x");store.reserve(a,"kit:test",30,"item",1000,9000);store.close();store=new CoreStore(dir.resolve("test.db"));
        assertEquals(70,store.balance(a));assertEquals(1,store.pending(a).size());assertEquals(10000,store.ready(a,"kit:test"));
    }
    @Test void olderAckCannotDeleteNewSnapshot() throws Exception {
        store.enqueue(a,"first","{}");store.enqueue(a,"second","{}");store.acknowledge("first");assertEquals("second",store.queued().getFirst().id());store.acknowledge("second");assertTrue(store.queued().isEmpty());
    }
    @Test void sequencePersistsAcrossRestart() throws Exception {
        long first=store.nextSequence();store.close();store=new CoreStore(dir.resolve("test.db"));assertTrue(store.nextSequence()>first);
    }
    @Test void auditWrittenWithMutation() throws Exception {
        store.change(a,10,"set","owner");assertTrue(store.recentAudit().getFirst().contains("coins.set"));
    }
    @Test void concurrentTransfersCannotOverspend() throws Exception {
        store.change(a,100,"set","x");
        try(ExecutorService pool=Executors.newFixedThreadPool(8)){
            List<Callable<Void>> tasks=new ArrayList<>();for(int i=0;i<200;i++)tasks.add(()->{try{store.transfer(a,b,1);}catch(IllegalArgumentException ignored){}return null;});
            for(Future<Void> f:pool.invokeAll(tasks))f.get();
        }assertEquals(0,store.balance(a));assertEquals(100,store.balance(b));
    }
    @Test void failedAuditRollsBackMoney() throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("test.db"));Statement s=c.createStatement()){
            s.execute("CREATE TRIGGER fail_audit BEFORE INSERT ON audit BEGIN SELECT RAISE(ABORT,'test'); END");
        }
        assertThrows(SQLException.class,()->store.change(a,10,"set","x"));assertEquals(0,store.balance(a));
    }
    @Test void committedCoinChangeRemainsDirtyUntilSnapshot() throws Exception {
        store.change(a,1,"add","owner");assertTrue(store.dirtyPlayers().contains(a));
        store.enqueue(a,"legacy","{}",false);assertTrue(store.dirtyPlayers().contains(a));
        store.enqueue(a,"snapshot","{}",true);assertFalse(store.dirtyPlayers().contains(a));
    }

}
