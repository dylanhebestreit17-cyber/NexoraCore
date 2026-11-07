package net.nexora.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ShopTest {
    @TempDir Path dir;
    CoreStore db;
    UUID buyer=UUID.randomUUID();
    @BeforeEach void open() throws Exception {db=new CoreStore(dir.resolve("shop.db"));db.seen(buyer,"RealTestFixture",1);db.change(buyer,100,"set","TEST");}
    @AfterEach void close() throws Exception {db.close();}
    ShopProduct create(String id,String currency,String kind,String reward) throws Exception {
        ShopProduct p=new ShopProduct(id,"Testfixture",20,currency,"Testdaten, keine Liveprodukte","test",true,kind,reward,1,false);
        db.productChange(p,true,0,"TEST");return db.product(id);
    }
    ShopProduct copy(ShopProduct p,long price,boolean active,boolean deleted){return new ShopProduct(p.id(),p.name(),price,p.currency(),p.description(),p.category(),active,p.kind(),p.reward(),p.revision(),deleted);}
    CoreStore.ShopOrder buy(String id,ShopProduct p,String reference) throws Exception {
        return db.purchase(id,buyer,p.id(),p.revision(),p.price(),p.currency(),p.kind(),p.reward(),reference);
    }
    @Test void exactEuroParsing(){assertEquals(1299,ShopProduct.price("12.99","eur"));assertEquals(10,ShopProduct.price("0,10","eur"));}
    @Test void exactCoinParsing(){assertEquals(123,ShopProduct.price("123","coins"));}
    @Test void rejectsInvalidPrices(){for(String v:List.of("-1","NaN","Infinity","1e5","+1","1.001"," 1"))assertThrows(IllegalArgumentException.class,()->ShopProduct.price(v,"eur"));}
    @Test void rejectsFractionalCoins(){assertThrows(IllegalArgumentException.class,()->ShopProduct.price("1.00","coins"));}
    @Test void rejectsPriceOverflow(){assertThrows(IllegalArgumentException.class,()->ShopProduct.price("9223372036854775807","eur"));}
    @Test void rejectsUnknownCurrency(){assertThrows(IllegalArgumentException.class,()->ShopProduct.currency("usd"));}
    @Test void rejectsUnsafeIds(){assertThrows(IllegalArgumentException.class,()->ShopProduct.validId("../../secret"));assertThrows(IllegalArgumentException.class,()->ShopProduct.validId("a.b"));}
    @Test void rejectsControlCharacters(){assertThrows(IllegalArgumentException.class,()->ShopProduct.text("a\nb",80));}
    @Test void createDefaultsStayInactive() throws Exception {
        db.productChange(new ShopProduct("empty","Empty",0,"eur","","custom",false,"custom","",1,false),true,0,"TEST");assertFalse(db.product("empty").active());
    }
    @Test void activeProductNeedsReward(){assertThrows(IllegalArgumentException.class,()->db.productChange(new ShopProduct("empty","Empty",0,"coins","","custom",true,"custom","",1,false),true,0,"TEST"));}
    @Test void stalePriceRevisionIsRejected() throws Exception {
        ShopProduct p=create("one","coins","coins","5");db.productChange(copy(p,21,true,false),false,p.revision(),"TEST");
        assertThrows(IllegalArgumentException.class,()->buy(UUID.randomUUID().toString(),p,null));assertEquals(100,db.balance(buyer));
    }
    @Test void staleEditIsRejected() throws Exception {
        ShopProduct p=create("one","coins","coins","5");db.productChange(copy(p,21,true,false),false,1,"TEST");
        assertThrows(IllegalArgumentException.class,()->db.productChange(copy(p,22,true,false),false,1,"TEST"));assertEquals(21,db.product("one").price());
    }
    @Test void deletionPreservesHistoryAndReservesId() throws Exception {
        ShopProduct p=create("one","coins","coins","5");String id=UUID.randomUUID().toString();buy(id,p,null);
        db.productChange(copy(p,20,false,true),false,1,"TEST");assertNotNull(db.order(id));assertTrue(db.product("one").deleted());
        assertThrows(IllegalArgumentException.class,()->db.productChange(p,true,0,"TEST"));
    }
    @Test void disabledProductCannotBeBought() throws Exception {
        ShopProduct p=create("one","coins","coins","5");db.productChange(copy(p,20,false,false),false,1,"TEST");
        assertThrows(IllegalArgumentException.class,()->buy(UUID.randomUUID().toString(),db.product("one"),null));assertEquals(100,db.balance(buyer));
    }
    @Test void insufficientFundsDoesNotCreateOrder() throws Exception {
        ShopProduct p=create("one","coins","coins","5");db.change(buyer,0,"set","TEST");String id=UUID.randomUUID().toString();
        assertThrows(IllegalArgumentException.class,()->buy(id,p,null));assertNull(db.order(id));
    }
    @Test void duplicateOrderDebitsOnce() throws Exception {
        ShopProduct p=create("one","coins","coins","5");String id=UUID.randomUUID().toString();buy(id,p,null);buy(id,p,null);assertEquals(80,db.balance(buyer));
    }
    @Test void reusedOrderIdWithDifferentBuyerRejected() throws Exception {
        ShopProduct p=create("one","coins","coins","5");String id=UUID.randomUUID().toString();buy(id,p,null);
        assertThrows(IllegalArgumentException.class,()->db.purchase(id,UUID.randomUUID(),p.id(),1,20,"coins","coins","5",null));
    }
    @Test void manipulatedPriceRejected() throws Exception {
        ShopProduct p=create("one","coins","coins","5");assertThrows(IllegalArgumentException.class,()->db.purchase(UUID.randomUUID().toString(),buyer,p.id(),1,0,"coins","coins","5",null));assertEquals(100,db.balance(buyer));
    }
    @Test void manipulatedRewardRejected() throws Exception {
        ShopProduct p=create("one","coins","coins","5");assertThrows(IllegalArgumentException.class,()->db.purchase(UUID.randomUUID().toString(),buyer,p.id(),1,20,"coins","coins","999",null));
    }
    @Test void coinRewardExactlyOnce() throws Exception {
        ShopProduct p=create("one","coins","coins","5");String id=UUID.randomUUID().toString();buy(id,p,null);db.coinOrder(id,5);db.coinOrder(id,5);assertEquals(85,db.balance(buyer));assertEquals("fulfilled",db.order(id).status());
    }
    @Test void duplicatePaymentReferenceRollsBack() throws Exception {
        ShopProduct p=create("one","eur","coins","5");buy(UUID.randomUUID().toString(),p,"provider:verified-test");
        assertThrows(SQLException.class,()->buy(UUID.randomUUID().toString(),p,"provider:verified-test"));assertEquals(1,db.orders(0,20).size());assertEquals(100,db.balance(buyer));
    }
    @Test void eurNeedsPaymentReference() throws Exception {
        ShopProduct p=create("one","eur","coins","5");assertThrows(IllegalArgumentException.class,()->buy(UUID.randomUUID().toString(),p,null));
    }
    @Test void claimOnceKeepsAmbiguousRankPending() throws Exception {
        ShopProduct p=create("one","coins","rank","vip_fixture");String id=UUID.randomUUID().toString();buy(id,p,null);
        assertTrue(db.claimOrder(id,null));assertFalse(db.claimOrder(id,null));assertEquals("delivering",db.order(id).status());db.finishOrder(id,"TEST");assertEquals("fulfilled",db.order(id).status());
    }
    @Test void inventoryJournalAndOrderResolveTogether() throws Exception {
        ShopProduct p=create("one","coins","item","fixture-bytes");String id=UUID.randomUUID().toString();buy(id,p,null);
        assertTrue(db.claimOrder(id,"fixture-bytes"));db.resolve(id,false,"TEST");assertEquals(100,db.balance(buyer));assertEquals("refunded",db.order(id).status());assertThrows(IllegalArgumentException.class,()->db.resolve(id,false,"TEST"));
    }
    @Test void eurRefundNeverCreatesLocalCoins() throws Exception {
        ShopProduct p=create("one","eur","item","fixture-bytes");String id=UUID.randomUUID().toString();buy(id,p,"provider:fixture");db.claimOrder(id,"fixture-bytes");db.resolve(id,false,"TEST");
        assertEquals(100,db.balance(buyer));assertEquals("refund_required",db.order(id).status());
    }
    @Test void manualReviewRefundIsOnce() throws Exception {
        ShopProduct p=create("one","coins","rank","vip_fixture");String id=UUID.randomUUID().toString();buy(id,p,null);db.claimOrder(id,null);db.reviewOrder(id,false,"TEST");
        assertEquals(100,db.balance(buyer));assertThrows(IllegalArgumentException.class,()->db.reviewOrder(id,false,"TEST"));
    }
    @Test void ownerJobAndMutationAreIdempotent() throws Exception {
        String id=UUID.randomUUID().toString();ShopProduct p=new ShopProduct("one","Fixture",10,"coins","","custom",false,"custom","",1,false);
        db.ownerJob(id,"owner-fixture","request",()->db.productMutation(p,true,0,"TEST"));
        db.ownerJob(id,"owner-fixture","request",()->{throw new AssertionError("must not execute twice");});
        assertEquals("applied",db.jobResult(id,"owner-fixture","request"));assertEquals(1,db.product("one").revision());
    }
    @Test void ownerJobConflictRejected() throws Exception {
        String id=UUID.randomUUID().toString();db.ownerJob(id,"owner-fixture","one",()->{});
        assertThrows(IllegalArgumentException.class,()->db.ownerJob(id,"other","one",()->{}));assertThrows(IllegalArgumentException.class,()->db.jobResult(id,"owner-fixture","two"));
    }
    @Test void failedOwnerJobRollsBack() throws Exception {
        String id=UUID.randomUUID().toString();ShopProduct p=new ShopProduct("one","Fixture",10,"coins","","custom",false,"custom","",1,false);
        assertThrows(IllegalArgumentException.class,()->db.ownerJob(id,"owner-fixture","request",()->{db.productMutation(p,true,0,"TEST");throw new IllegalArgumentException("rollback");}));
        assertNull(db.product("one"));assertNull(db.jobResult(id,"owner-fixture","request"));
    }
    @Test void readyOrdersAreNotHiddenBehindHistoryPages() throws Exception {
        ShopProduct p=create("one","coins","coins","1");db.change(buyer,10000,"set","TEST");
        for(int i=0;i<105;i++){String id=UUID.randomUUID().toString();buy(id,p,null);db.coinOrder(id,1);}
        String pending=UUID.randomUUID().toString();buy(pending,p,null);assertEquals(pending,db.readyOrders(buyer).getFirst().id());
    }
    @Test void restartPreservesShopAndBalances() throws Exception {
        ShopProduct p=create("one","coins","rank","vip_fixture");String id=UUID.randomUUID().toString();buy(id,p,null);db.claimOrder(id,null);db.close();db=new CoreStore(dir.resolve("shop.db"));
        assertEquals(80,db.balance(buyer));assertEquals("delivering",db.order(id).status());assertNotNull(db.product("one"));
    }
    @Test void schemaOneBackupBeforeMigrationRetainsMoney() throws Exception {
        db.close();
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("shop.db"));Statement s=c.createStatement()){
            s.execute("DROP TABLE shop_products");s.execute("DROP TABLE shop_orders");s.execute("DROP TABLE shop_jobs");s.execute("PRAGMA user_version=1");
        }
        Set<Path> before;try(var paths=Files.list(dir)){before=new HashSet<>(paths.toList());}
        db=new CoreStore(dir.resolve("shop.db"));assertEquals(100,db.balance(buyer));
        Path backup;try(var paths=Files.list(dir)){backup=paths.filter(p->p.toString().endsWith(".bak")&&!before.contains(p)).findFirst().orElseThrow();}
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+backup);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT coins FROM accounts WHERE uuid='"+buyer+"'")){assertTrue(r.next());assertEquals(100,r.getLong(1));}
    }
}
