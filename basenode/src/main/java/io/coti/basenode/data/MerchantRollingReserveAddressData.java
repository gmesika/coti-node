package io.coti.basenode.data;

import io.coti.basenode.data.interfaces.IPropagatable;
import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class MerchantRollingReserveAddressData implements IPropagatable {
    @NotNull
    private Hash merchantHash;
    @NotNull
    private Hash merchantRollingReserveAddress;

    public MerchantRollingReserveAddressData(Hash merchantHash, Hash merchantRollingReserveAddress) {
        this.merchantHash = merchantHash;
        this.merchantRollingReserveAddress = merchantRollingReserveAddress;
    }

    @Override
    public Hash getHash() {
        return merchantHash;
    }

    @Override
    public void setHash(Hash hash) {
        merchantHash = hash;
    }
}
