package io.coti.basenode.services;

import io.coti.basenode.data.Event;
import io.coti.basenode.data.Hash;
import io.coti.basenode.data.InputBaseTransactionData;
import io.coti.basenode.data.TransactionData;
import io.coti.basenode.http.GetTokenBalancesRequest;
import io.coti.basenode.http.GetTokenBalancesResponse;
import io.coti.basenode.http.interfaces.IResponse;
import io.coti.basenode.services.interfaces.ICurrencyService;
import io.coti.basenode.services.interfaces.IEventService;
import io.coti.basenode.utils.TransactionTestUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static io.coti.basenode.utils.HashTestUtils.generateRandomAddressHash;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.when;

@ContextConfiguration(classes = {BaseNodeCurrencyService.class, BaseNodeBalanceService.class})

@TestPropertySource(locations = "classpath:test.properties")
@SpringBootTest
@ExtendWith(SpringExtension.class)
@Slf4j
public class BaseNodeBalanceServiceTest {

    @Autowired
    private BaseNodeBalanceService balanceService;
    @MockBean
    protected ICurrencyService currencyService;
    @MockBean
    protected IEventService nodeEventService;

    @BeforeEach
    public void init() {
        balanceService.init();
    }

    @Test
    public void getCurrencyBalances_noNative_valuesMatch() {
        when(nodeEventService.eventHappened(isA(Event.MULTI_DAG.getClass()))).thenReturn(true);
        when(currencyService.getNativeCurrencyHashIfNull(any(Hash.class))).then(a -> a.getArgument(0));

        GetTokenBalancesRequest getCurrencyBalanceRequest = new GetTokenBalancesRequest();
        Hash tokenHash1 = generateRandomAddressHash();
        Hash tokenHash2 = generateRandomAddressHash();
        Hash addressHash1 = generateRandomAddressHash();
        Hash addressHash2 = generateRandomAddressHash();
        List<Hash> addresses = Arrays.asList(addressHash1, addressHash2);
        getCurrencyBalanceRequest.setAddresses(addresses);

        HashMap<Hash, BigDecimal> currencyHashToAmountMap1 = new HashMap<>();
        currencyHashToAmountMap1.put(tokenHash1, BigDecimal.TEN);
        currencyHashToAmountMap1.put(tokenHash2, BigDecimal.ONE);
        balanceService.balanceMap.put(addressHash1, currencyHashToAmountMap1);
        balanceService.balanceMap.put(addressHash2, currencyHashToAmountMap1);
        HashMap<Hash, BigDecimal> currencyHashToAmountMap2 = new HashMap<>();
        currencyHashToAmountMap2.put(tokenHash1, BigDecimal.ZERO);
        currencyHashToAmountMap2.put(tokenHash2, BigDecimal.ONE);
        balanceService.preBalanceMap.put(addressHash1, currencyHashToAmountMap2);
        balanceService.preBalanceMap.put(addressHash2, currencyHashToAmountMap2);
        ResponseEntity<IResponse> currencyBalances = balanceService.getTokenBalances(getCurrencyBalanceRequest);

        Assertions.assertEquals(HttpStatus.OK, currencyBalances.getStatusCode());
        Assertions.assertTrue(((GetTokenBalancesResponse) Objects.requireNonNull(currencyBalances.getBody())).getTokenBalances().get(addressHash1).containsKey(tokenHash1));
        Assertions.assertTrue(((GetTokenBalancesResponse) currencyBalances.getBody()).getTokenBalances().get(addressHash1).containsKey(tokenHash2));
        Assertions.assertEquals(BigDecimal.TEN, ((GetTokenBalancesResponse) currencyBalances.getBody()).getTokenBalances().get(addressHash1).get(tokenHash1).getAddressBalance());
        Assertions.assertEquals(BigDecimal.ONE, ((GetTokenBalancesResponse) currencyBalances.getBody()).getTokenBalances().get(addressHash1).get(tokenHash2).getAddressBalance());
    }

    @Test
    public void rollbackBaseTransactions() {
        TransactionData transactionData = TransactionTestUtils.createRandomTransaction();
        Map<Hash, Map<Hash, BigDecimal>> preBalanceMap = new ConcurrentHashMap<>();
        AtomicReference<Hash> currencyHash = new AtomicReference<>();
        transactionData.getBaseTransactions().forEach(baseTransactionData -> {
            BigDecimal amount = baseTransactionData.getAmount();
            Hash addressHash = baseTransactionData.getAddressHash();
            currencyHash.set(baseTransactionData.getCurrencyHash());
            preBalanceMap.putIfAbsent(addressHash, new ConcurrentHashMap<>());
            preBalanceMap.get(addressHash).merge(currencyHash.get(), amount, (a, b) -> b.add(a));
            when(currencyService.getNativeCurrencyHashIfNull(baseTransactionData.getCurrencyHash())).thenReturn(baseTransactionData.getCurrencyHash());
        });
        ReflectionTestUtils.setField(balanceService, "preBalanceMap", preBalanceMap);
        balanceService.rollbackBaseTransactions(transactionData);
        Assertions.assertEquals(BigDecimal.ZERO, preBalanceMap.get(transactionData.getBaseTransactions().get(0).getAddressHash()).get(currencyHash.get()));
    }

    @Test
    public void checkBalancesAndAddToPreBalance_balance_below_zero() {
        TransactionData transactionData = TransactionTestUtils.createRandomTransaction();
        transactionData.getBaseTransactions().forEach(baseTransactionData ->
                when(currencyService.getNativeCurrencyHashIfNull(baseTransactionData.getCurrencyHash())).thenReturn(baseTransactionData.getCurrencyHash()));
        Assertions.assertFalse(balanceService.checkBalancesAndAddToPreBalance(transactionData.getBaseTransactions()));
    }

    @Test
    public void checkBalancesAndAddToPreBalance_success() {
        TransactionData transactionData = TransactionTestUtils.createRandomTransaction();
        transactionData.getBaseTransactions().forEach(baseTransactionData -> {
            if (baseTransactionData instanceof InputBaseTransactionData) {
                Hash addressHash = baseTransactionData.getAddressHash();
                balanceService.balanceMap.putIfAbsent(addressHash, new ConcurrentHashMap<>());
                balanceService.balanceMap.get(addressHash).putIfAbsent(baseTransactionData.getCurrencyHash(), new BigDecimal(10));
                balanceService.preBalanceMap.putIfAbsent(addressHash, new ConcurrentHashMap<>());
                balanceService.preBalanceMap.get(addressHash).putIfAbsent(baseTransactionData.getCurrencyHash(), new BigDecimal(10));
            }
            when(currencyService.getNativeCurrencyHashIfNull(baseTransactionData.getCurrencyHash())).thenReturn(baseTransactionData.getCurrencyHash());
        });
        Assertions.assertTrue(balanceService.checkBalancesAndAddToPreBalance(transactionData.getBaseTransactions()));
    }
}
