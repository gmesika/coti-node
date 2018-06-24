package io.coti.cotinode.service.interfaces;

import io.coti.cotinode.data.Hash;
import io.coti.cotinode.data.ConfirmationData;

import java.util.List;
import java.util.Map;

public interface IBalanceService {

    public boolean checkBalancesAndAddToPreBalance(List<Map.Entry<Hash, Double>> pairList);

    public void insertIntoUnconfirmedDBandAddToTccQeueue(ConfirmationData confirmationData);
}
