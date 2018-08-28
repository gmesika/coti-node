package io.coti.common.services;

import io.coti.common.data.Hash;
import io.coti.common.data.TccInfo;
import io.coti.common.data.TransactionData;
import io.coti.common.model.Transactions;
import io.coti.common.services.LiveView.LiveViewService;
import io.coti.common.services.interfaces.IBalanceService;
import io.coti.common.services.interfaces.IClusterService;
import io.coti.common.services.interfaces.ISourceSelector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class ClusterService implements IClusterService {
    private List<List<TransactionData>> sourceListsByTrustScore = new ArrayList<>();

    @Autowired
    private Transactions transactions;
    @Autowired
    private IBalanceService balanceService;
    @Autowired
    private ISourceSelector sourceSelector;
    @Autowired
    private TccConfirmationService tccConfirmationService;
    @Autowired
    private LiveViewService liveViewService;
    private boolean isStarted;
    private ConcurrentHashMap<Hash, TransactionData> hashToUnconfirmedTransactionsMapping;
    private AtomicLong totalSources = new AtomicLong(0);

    @PostConstruct
    public void init() {
        hashToUnconfirmedTransactionsMapping = new ConcurrentHashMap<>();
        for (int i = 0; i <= 100; i++) {
            sourceListsByTrustScore.add(new ArrayList<>());
        }
    }

    @Override
    public void addUnconfirmedTransaction(TransactionData transactionData) {
        hashToUnconfirmedTransactionsMapping.put(transactionData.getHash(), transactionData);
        sourceListsByTrustScore.get(transactionData.getRoundedSenderTrustScore()).add(transactionData);
        if (transactionData.getChildrenTransactions() == null || transactionData.getChildrenTransactions().isEmpty()) {
            totalSources.incrementAndGet();
        }
    }

    @Override
    public void finalizeInit() {
        isStarted = true;
        log.info("Cluster Service is up");
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 1000)
    public void checkForTrustChainConfirmedTransaction() {
        if (!isStarted) {
            return;
        }

        tccConfirmationService.init(hashToUnconfirmedTransactionsMapping);
        List<TccInfo> transactionConsensusConfirmed = tccConfirmationService.getTccConfirmedTransactions();

        transactionConsensusConfirmed.forEach(tccInfo -> {
            hashToUnconfirmedTransactionsMapping.remove(tccInfo.getHash());
            balanceService.setTccToTrue(tccInfo);
            log.debug("TCC has been reached for transaction {}!!", tccInfo.getHash());
        });
    }

    @Override
    public void attachToCluster(TransactionData transactionData) {
        if (transactionData.getChildrenTransactions() == null) {
            transactionData.setChildrenTransactions(new LinkedList<>());
        }

        updateParents(transactionData);
        hashToUnconfirmedTransactionsMapping.put(transactionData.getHash(), transactionData);
        removeTransactionParentsFromSources(transactionData);
        sourceListsByTrustScore.get(transactionData.getRoundedSenderTrustScore()).add(transactionData);
        totalSources.incrementAndGet();
        log.debug("Added New Transaction with hash:{}", transactionData.getHash());
        liveViewService.addNode(transactionData);
        return;
    }

    private void updateParents(TransactionData transactionData) {
        updateSingleParent(transactionData, transactionData.getLeftParentHash());
        updateSingleParent(transactionData, transactionData.getRightParentHash());
    }

    private void updateSingleParent(TransactionData transactionData, Hash parentHash) {
        if (parentHash != null) {
            TransactionData parentTransactionData = transactions.getByHash(parentHash);
            parentTransactionData.addToChildrenTransactions(transactionData.getHash());
            hashToUnconfirmedTransactionsMapping.put(parentTransactionData.getHash(), parentTransactionData);
            transactions.put(parentTransactionData);
        }
    }

    private void removeTransactionParentsFromSources(TransactionData transactionData) {
        if (transactionData.getLeftParentHash() != null) {
            removeTransactionFromSources(transactionData.getLeftParentHash());
        }
        if (transactionData.getRightParentHash() != null) {
            removeTransactionFromSources(transactionData.getRightParentHash());
        }
    }

    private void removeTransactionFromSources(Hash transactionHash) {
        TransactionData transactionData = transactions.getByHash(transactionHash);
        if (sourceListsByTrustScore.get(transactionData.getRoundedSenderTrustScore()).contains(transactionData)) {
            sourceListsByTrustScore.get(transactionData.getRoundedSenderTrustScore()).remove(transactionData); // TODO: synchronize
            liveViewService.updateNodeStatus(transactionData, 1);
            totalSources.decrementAndGet();
        }
    }

    @Override
    public void selectSources(TransactionData transactionData) {
        List<List<TransactionData>> trustScoreToTransactionMappingSnapshot =
                Collections.unmodifiableList(sourceListsByTrustScore);

        List<TransactionData> selectedSourcesForAttachment =
                sourceSelector.selectSourcesForAttachment(
                        trustScoreToTransactionMappingSnapshot,
                        transactionData.getSenderTrustScore());

        if (selectedSourcesForAttachment.size() == 0) {
            log.info("No sources were found for transaction:{} ", transactionData.getHash());
            return;
        }
        if (selectedSourcesForAttachment.size() > 0) {
            transactionData.setLeftParentHash(selectedSourcesForAttachment.get(0).getHash());
        }
        if (selectedSourcesForAttachment.size() > 1) {
            transactionData.setRightParentHash(selectedSourcesForAttachment.get(1).getHash());
        }

        List<Hash> selectedSourceHashes = new LinkedList<>();
        selectedSourcesForAttachment.forEach(source -> selectedSourceHashes.add(source.getHash()));
        log.debug("For transaction with hash:{} we found the following sources:{}", transactionData.getHash(), selectedSourceHashes);

        return;
    }

    public List<List<TransactionData>> getSourceListsByTrustScore() {
        return sourceListsByTrustScore;
    }

    @Override
    public long getTotalSources() {
        return totalSources.get();
    }

}