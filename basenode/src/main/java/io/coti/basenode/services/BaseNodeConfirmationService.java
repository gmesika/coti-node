package io.coti.basenode.services;

import io.coti.basenode.data.*;
import io.coti.basenode.model.TransactionIndexes;
import io.coti.basenode.model.Transactions;
import io.coti.basenode.services.interfaces.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class BaseNodeConfirmationService implements IConfirmationService {

    @Autowired
    private IBalanceService balanceService;
    @Autowired
    private ICurrencyService currencyService;
    @Autowired
    private IMintingService mintingService;
    @Autowired
    private ITransactionHelper transactionHelper;
    @Autowired
    private TransactionIndexService transactionIndexService;
    @Autowired
    private TransactionIndexes transactionIndexes;
    @Autowired
    private Transactions transactions;
    @Autowired
    private IEventService eventService;
    private BlockingQueue<ConfirmationData> confirmationQueue;
    private final Map<Long, DspConsensusResult> waitingDspConsensusResults = new ConcurrentHashMap<>();
    private final Map<Long, TransactionData> waitingMissingTransactionIndexes = new ConcurrentHashMap<>();
    private final AtomicLong totalConfirmed = new AtomicLong(0);
    private final AtomicLong trustChainConfirmed = new AtomicLong(0);
    private final AtomicLong dspConfirmed = new AtomicLong(0);
    private Thread confirmedTransactionsThread;
    private final Object initialConfirmationLock = new Object();
    private final AtomicBoolean initialConfirmationStarted = new AtomicBoolean(false);
    private final AtomicBoolean initialConfirmationFinished = new AtomicBoolean(false);

    public void init() {
        confirmationQueue = new LinkedBlockingQueue<>();
        confirmedTransactionsThread = new Thread(this::updateConfirmedTransactions, "Confirmation");
        confirmedTransactionsThread.start();
        log.info("{} is up", this.getClass().getSimpleName());
    }

    @Override
    public void setLastDspConfirmationIndex(Map<Long, ReducedExistingTransactionData> indexToTransactionMap) {
        log.info("Started to set last dsp confirmation index");
        byte[] accumulatedHash = "GENESIS".getBytes();
        TransactionIndexData transactionIndexData = new TransactionIndexData(new Hash(-1), -1, "GENESIS".getBytes());
        TransactionIndexData nextTransactionIndexData;
        try {
            for (long i = 0; i < indexToTransactionMap.size(); i++) {
                nextTransactionIndexData = transactionIndexes.getByHash(new Hash(i));
                if (nextTransactionIndexData == null) {
                    log.error("Null transaction index data found for index {}", i);
                    return;
                }

                ReducedExistingTransactionData reducedExistingTransactionData = indexToTransactionMap.get(i);
                if (reducedExistingTransactionData == null) {
                    log.error("Null transaction data found for index {}", i);
                    return;
                }
                accumulatedHash = transactionIndexService.getAccumulatedHash(accumulatedHash, reducedExistingTransactionData.getHash(), i);
                if (!Arrays.equals(accumulatedHash, nextTransactionIndexData.getAccumulatedHash())) {
                    log.error("Incorrect accumulated hash");
                    return;
                }
                dspConfirmed.incrementAndGet();
                if (reducedExistingTransactionData.isTrustChainConsensus()) {
                    totalConfirmed.incrementAndGet();
                    reducedExistingTransactionData.getAddressAmounts().forEach(reducedExistingBaseTransactionData ->
                            balanceService.updateBalance(reducedExistingBaseTransactionData.getAddressHash(), reducedExistingBaseTransactionData.getCurrencyHash(), reducedExistingBaseTransactionData.getAmount())
                    );
                }
                transactionIndexData = nextTransactionIndexData;
            }
        } finally {
            transactionIndexService.setLastTransactionIndexData(transactionIndexData);
            log.info("Finished to set last dsp confirmation index: {}", transactionIndexData.getIndex());
        }
    }

    private void updateConfirmedTransactions() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ConfirmationData confirmationData = confirmationQueue.take();
                updateConfirmedTransactionHandler(confirmationData);
                if (initialConfirmationStarted.get() && confirmationQueue.isEmpty() && !initialConfirmationFinished.get()) {
                    synchronized (initialConfirmationLock) {
                        initialConfirmationFinished.set(true);
                        initialConfirmationLock.notifyAll();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LinkedList<ConfirmationData> remainingConfirmedTransactions = new LinkedList<>();
        confirmationQueue.drainTo(remainingConfirmedTransactions);
        if (!remainingConfirmedTransactions.isEmpty()) {
            log.info("Please wait to process {} remaining confirmed transaction(s)", remainingConfirmedTransactions.size());
            remainingConfirmedTransactions.forEach(this::updateConfirmedTransactionHandler);
        }
    }

    private void updateConfirmedTransactionHandler(ConfirmationData confirmationData) {
        transactions.lockAndGetByHash(confirmationData.getHash(), transactionData -> {
            if (confirmationData instanceof TccInfo) {
                transactionData.setTrustChainConsensus(true);
                transactionData.setTrustChainConsensusTime(((TccInfo) confirmationData).getTrustChainConsensusTime());
                transactionData.setTrustChainTrustScore(((TccInfo) confirmationData).getTrustChainTrustScore());
                trustChainConfirmed.incrementAndGet();
            } else if (confirmationData instanceof DspConsensusResult) {
                transactionData.setDspConsensusResult((DspConsensusResult) confirmationData);
                if (!insertNewTransactionIndex(transactionData)) {
                    return;
                }
                if (transactionHelper.isDspConfirmed(transactionData)) {
                    continueHandleDSPConfirmedTransaction(transactionData);
                    dspConfirmed.incrementAndGet();
                }
            }
            if (transactionHelper.isConfirmed(transactionData)) {
                processConfirmedTransaction(transactionData);
            }
            transactions.put(transactionData);
        });

    }

    protected boolean insertNewTransactionIndex(TransactionData transactionData) {
        Optional<Boolean> optionalInsertNewTransactionIndex = transactionIndexService.insertNewTransactionIndex(transactionData);
        if (!optionalInsertNewTransactionIndex.isPresent()) {
            return false;
        }
        Boolean isNewTransactionIndexInserted = optionalInsertNewTransactionIndex.get();
        DspConsensusResult dspConsensusResult = transactionData.getDspConsensusResult();
        if (Boolean.FALSE.equals(isNewTransactionIndexInserted)) {
            waitingDspConsensusResults.put(dspConsensusResult.getIndex(), dspConsensusResult);
            return false;
        } else {
            long index = dspConsensusResult.getIndex() + 1;
            while (waitingDspConsensusResults.containsKey(index)) {
                setDspcToTrue(waitingDspConsensusResults.get(index));
                waitingDspConsensusResults.remove(index);
                index++;
            }
            return true;
        }
    }

    private void processConfirmedTransaction(TransactionData transactionData) {
        Instant trustChainConsensusTime = transactionData.getTrustChainConsensusTime();
        Instant dspConsensusTime = transactionData.getDspConsensusResult().getIndexingTime();
        Instant transactionConsensusUpdateTime = trustChainConsensusTime.isAfter(dspConsensusTime) ? trustChainConsensusTime : dspConsensusTime;
        transactionData.setTransactionConsensusUpdateTime(transactionConsensusUpdateTime);
        transactionData.getBaseTransactions().forEach(baseTransactionData -> balanceService.updateBalance(baseTransactionData.getAddressHash(), baseTransactionData.getCurrencyHash(), baseTransactionData.getAmount()));
        totalConfirmed.incrementAndGet();

        transactionData.getBaseTransactions().forEach(baseTransactionData -> {
            Hash addressHash = baseTransactionData.getAddressHash();
            Hash currencyHash = baseTransactionData.getCurrencyHash();
            balanceService.continueHandleBalanceChanges(addressHash, currencyHash);
        });

        if (transactionData.getType().equals(TransactionType.TokenGeneration)) {
            currencyService.addConfirmedCurrency(transactionData);
        }

        if (transactionData.getType().equals(TransactionType.TokenMinting)) {
            mintingService.doTokenMinting(transactionData);
            continueHandleTokenChanges(transactionData);
        }

        continueHandleAddressHistoryChanges(transactionData);
        continueHandleConfirmedTransaction(transactionData);
    }

    protected void continueHandleDSPConfirmedTransaction(TransactionData transactionData) {
        if (eventService.eventHappened(Event.TRUST_SCORE_CONSENSUS) && !transactionData.isTrustChainConsensus()) {
            transactionHelper.updateTransactionOnCluster(transactionData);
        }
    }

    protected void continueHandleAddressHistoryChanges(TransactionData transactionData) {
        // implemented by the sub classes
    }

    protected void continueHandleConfirmedTransaction(TransactionData transactionData) {
        // implemented by the sub classes
    }

    @Override
    public void insertSavedTransaction(TransactionData transactionData, Map<Long, ReducedExistingTransactionData> indexToTransactionMap) {
        boolean isDspConfirmed = transactionHelper.isDspConfirmed(transactionData);
        transactionData.getBaseTransactions().forEach(baseTransactionData ->
                balanceService.updatePreBalance(baseTransactionData.getAddressHash(), baseTransactionData.getCurrencyHash(), baseTransactionData.getAmount())
        );
        if (!isDspConfirmed) {
            transactionHelper.addNoneIndexedTransaction(transactionData);
        }
        if (transactionData.getDspConsensusResult() != null) {
            indexToTransactionMap.put(transactionData.getDspConsensusResult().getIndex(), new ReducedExistingTransactionData(transactionData));
        }

        if (transactionData.isTrustChainConsensus()) {
            trustChainConfirmed.incrementAndGet();
        }

    }

    @Override
    public void insertMissingTransaction(TransactionData transactionData) {
        transactionData.getBaseTransactions().forEach(baseTransactionData -> balanceService.updatePreBalance(baseTransactionData.getAddressHash(), baseTransactionData.getCurrencyHash(), baseTransactionData.getAmount()));
        if (transactionData.isTrustChainConsensus()) {
            trustChainConfirmed.incrementAndGet();
        }
        insertMissingDspConfirmation(transactionData);
    }

    @Override
    public void insertMissingConfirmation(TransactionData transactionData, Set<Hash> trustChainUnconfirmedExistingTransactionHashes) {
        if (trustChainUnconfirmedExistingTransactionHashes.contains(transactionData.getHash()) && transactionData.isTrustChainConsensus()) {
            trustChainConfirmed.incrementAndGet();
        }
        insertMissingDspConfirmation(transactionData);
    }

    private void insertMissingDspConfirmation(TransactionData transactionData) {
        if (!transactionHelper.isDspConfirmed(transactionData)) {
            transactionHelper.addNoneIndexedTransaction(transactionData);
        }
        if (transactionData.getDspConsensusResult() != null) {
            insertMissingTransactionIndex(transactionData);
        }
    }

    private void insertMissingTransactionIndex(TransactionData transactionData) {
        Optional<Boolean> optionalInsertNewTransactionIndex = transactionIndexService.insertNewTransactionIndex(transactionData);
        if (!optionalInsertNewTransactionIndex.isPresent()) {
            return;
        }
        Boolean isNewTransactionIndexInserted = optionalInsertNewTransactionIndex.get();
        DspConsensusResult dspConsensusResult = transactionData.getDspConsensusResult();
        if (Boolean.FALSE.equals(isNewTransactionIndexInserted)) {
            waitingMissingTransactionIndexes.put(dspConsensusResult.getIndex(), transactionData);
        } else {
            processMissingDspConfirmedTransaction(transactionData);
            long index = dspConsensusResult.getIndex() + 1;
            while (waitingMissingTransactionIndexes.containsKey(index)) {
                TransactionData waitingMissingTransactionData = waitingMissingTransactionIndexes.get(index);
                transactionIndexService.insertNewTransactionIndex(waitingMissingTransactionData);
                processMissingDspConfirmedTransaction(waitingMissingTransactionData);
                waitingMissingTransactionIndexes.remove(index);
                index++;
            }
        }
    }

    private void processMissingDspConfirmedTransaction(TransactionData transactionData) {
        continueHandleDSPConfirmedTransaction(transactionData);
        dspConfirmed.incrementAndGet();
        if (transactionData.isTrustChainConsensus()) {
            transactionData.getBaseTransactions().forEach(baseTransactionData -> balanceService.updateBalance(baseTransactionData.getAddressHash(), baseTransactionData.getCurrencyHash(), baseTransactionData.getAmount()));
            totalConfirmed.incrementAndGet();
            continueHandleConfirmedTransaction(transactionData);
        }
    }

    @Override
    public void setTccToTrue(TccInfo tccInfo) {
        try {
            confirmationQueue.put(tccInfo);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void setDspcToTrue(DspConsensusResult dspConsensusResult) {
        try {
            confirmationQueue.put(dspConsensusResult);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public long getTotalConfirmed() {
        return totalConfirmed.get();
    }

    @Override
    public long getTrustChainConfirmed() {
        return trustChainConfirmed.get();
    }

    @Override
    public long getDspConfirmed() {
        return dspConfirmed.get();
    }

    public void shutdown() {
        log.info("Shutting down {}", this.getClass().getSimpleName());
        confirmedTransactionsThread.interrupt();
        try {
            confirmedTransactionsThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted shutdown {}", this.getClass().getSimpleName());
        }

    }

    @Override
    public int getWaitingDspConsensusResultsMapSize() {
        return waitingDspConsensusResults.size();
    }

    @Override
    public int getWaitingMissingTransactionIndexesSize() {
        return waitingMissingTransactionIndexes.size();
    }

    @Override
    public int getQueueSize() {
        return confirmationQueue.size();
    }

    @Override
    public Object getInitialConfirmationLock() {
        return initialConfirmationLock;
    }

    @Override
    public AtomicBoolean getInitialConfirmationStarted() {
        return initialConfirmationStarted;
    }

    @Override
    public AtomicBoolean getInitialConfirmationFinished() {
        return initialConfirmationFinished;
    }

    protected void continueHandleTokenChanges(TransactionData transactionData) {
        // implemented by the sub classes
    }
}
