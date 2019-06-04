package io.coti.basenode.services;

import io.coti.basenode.data.AddressData;
import io.coti.basenode.data.Hash;
import io.coti.basenode.http.AddressFileRequest;
import io.coti.basenode.http.CustomGson;
import io.coti.basenode.http.Response;
import io.coti.basenode.http.data.AddressResponseData;
import io.coti.basenode.http.interfaces.IResponse;
import io.coti.basenode.model.Addresses;
import io.coti.basenode.services.interfaces.IAddressService;
import io.coti.basenode.services.interfaces.IValidationService;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksIterator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.SerializationUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.*;

@Slf4j
@Service
public class BaseNodeAddressService implements IAddressService {
    @Autowired
    private Addresses addresses;
    @Autowired
    private IValidationService validationService;

    public void init() {
        log.info("{} is up", this.getClass().getSimpleName());
    }

    public boolean addNewAddress(AddressData addressData) {
        if (!addressExists(addressData.getHash())) {
            addresses.put(addressData);
            log.info("Address {} was successfully inserted", addressData.getHash());
            return true;
        }
        log.debug("Address {} already exists", addressData.getHash());
        return false;
    }

    @Override
    public boolean addressExists(Hash addressHash) {
        return addresses.getByHash(addressHash) != null;
    }

    public void handlePropagatedAddress(AddressData addressData) {
        try {
            if (addressExists(addressData.getHash())) {
                log.debug("Address {} already exists", addressData.getHash());
                return;
            }
            if (!validateAddress(addressData.getHash())) {
                log.error("Invalid address {}", addressData.getHash());
                return;
            }
            addNewAddress(addressData);
            continueHandleGeneratedAddress(addressData);
        } catch (Exception e) {
            log.error("Error at handlePropagatedAddress");
            e.printStackTrace();
        }
    }

    protected void continueHandleGeneratedAddress(AddressData addressData) {

    }

    @Override
    public boolean validateAddress(Hash addressHash) {
        return validationService.validateAddress(addressHash);
    }

    @Override
    public void getAddressBatch(HttpServletResponse response) {
        try {
            PrintWriter output = response.getWriter();
            output.write("[");
            output.flush();

            RocksIterator iterator = addresses.getIterator();
            iterator.seekToFirst();
            while (iterator.isValid()) {
                AddressData addressData = (AddressData) SerializationUtils.deserialize(iterator.value());
                addressData.setHash(new Hash(iterator.key()));
                output.write(new CustomGson().getInstance().toJson(new AddressResponseData(addressData)));
                iterator.next();
                if (iterator.isValid()) {
                    output.write(",");
                }
                output.flush();
            }
            output.write("]");
            output.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public ResponseEntity<IResponse> addAddressBatch(AddressFileRequest request) {
        MultipartFile multiPartFile = request.getFile();

        String fileName = "addressBatch.txt";
        File file = new File(fileName);

        try {
            if (file.createNewFile()) {
                FileOutputStream fileOutputStream = new FileOutputStream(file);
                fileOutputStream.write(multiPartFile.getBytes());
                fileOutputStream.close();
            }
        } catch (IOException e) {

        }
        String line = "";
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(fileName))) {
            while ((line = bufferedReader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    break;
                }
                String[] lineSplits = line.split(",");
                for (int i = 0; i < lineSplits.length; i++) {
                    if (lineSplits[i].contains("address")) {
                        String[] subLineSplits = lineSplits[i].split(":");
                        String address = subLineSplits[1];
                        addresses.put(new AddressData(new Hash(address.replaceAll("\"", ""))));
                    }
                }
            }
        } catch (Exception e) {
        }
        return ResponseEntity.status(HttpStatus.OK).body(new Response());
    }
}