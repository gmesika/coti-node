package io.coti.basenode.crypto;

import io.coti.basenode.data.Hash;
import io.coti.basenode.data.SignatureData;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.math.ec.ECPoint;

import javax.xml.bind.DatatypeConverter;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

public class CryptoHelper {

    private static final String EC_SPEC = "secp256k1";
    private static final String EC_ALGORITHM = "ECDSA";
    private static final X9ECParameters curve = SECNamedCurves.getByName(EC_SPEC);
    private static final ECDomainParameters domain = new ECDomainParameters(curve.getCurve(), curve.getG(), curve.getN(), curve.getH());
    private static final ECParameterSpec spec = new ECParameterSpec(curve.getCurve(), curve.getG(), curve.getN(), curve.getH());
    public static final int ADDRESS_SIZE_IN_BYTES = 68;
    public static final int ADDRESS_CHECKSUM_SIZE_IN_BYTES = 4;
    public static final int DEFAULT_HASH_BYTE_SIZE = 32;

    private CryptoHelper() {

    }

    public static PublicKey getPublicKeyFromHexString(String pubKeyHex) throws NoSuchAlgorithmException, InvalidKeySpecException {
        String pointX = pubKeyHex.substring(0, (pubKeyHex.length() / 2));
        String pointY = pubKeyHex.substring(pubKeyHex.length() / 2);

        BigInteger p256X = new BigInteger(pointX, 16);
        BigInteger p256Y = new BigInteger(pointY, 16);

        ECPoint point = curve.getCurve().createPoint(p256X, p256Y);
        ECPublicKeySpec publicSpec = new ECPublicKeySpec(point, spec);
        KeyFactory keyfac = KeyFactory.getInstance(EC_ALGORITHM, new BouncyCastleProvider());

        return keyfac.generatePublic(publicSpec);
    }

    public static boolean verifyByPublicKey(byte[] originalMessageToVerify, String rHex, String sHex, String publicKey) throws InvalidKeySpecException, NoSuchAlgorithmException {
        return verifyByPublicKey(originalMessageToVerify, rHex, sHex, getPublicKeyFromHexString(publicKey));
    }

    public static byte[] removeLeadingZerosFromAddress(byte[] addressBytesWithoutChecksum) {
        byte[] xPart = Arrays.copyOfRange(addressBytesWithoutChecksum, 0, addressBytesWithoutChecksum.length / 2);
        byte[] yPart = Arrays.copyOfRange(addressBytesWithoutChecksum, addressBytesWithoutChecksum.length / 2, addressBytesWithoutChecksum.length);

        byte[] xPointPart = new byte[0];
        byte[] yPointPart = new byte[0];

        for (int i = 0; i < xPart.length; i++) {
            if (xPart[i] != 0) {
                xPointPart = Arrays.copyOfRange(xPart, i, xPart.length);
                break;
            }
        }

        for (int i = 0; i < yPart.length; i++) {
            if (yPart[i] != 0) {
                yPointPart = Arrays.copyOfRange(yPart, i, yPart.length);
                break;
            }
        }

        ByteBuffer addressBuffer = ByteBuffer.allocate(xPointPart.length + yPointPart.length);
        addressBuffer.put(xPointPart);
        addressBuffer.put(yPointPart);
        return addressBuffer.array();
    }

    public static SignatureData signBytes(byte[] bytesToSign, String privateKeyHex) {

        byte[] privateKey = DatatypeConverter.parseHexBinary(privateKeyHex);
        ECDSASigner signer = new ECDSASigner();
        signer.init(true, new ECPrivateKeyParameters(new BigInteger(1, privateKey), domain));
        BigInteger[] signature = signer.generateSignature(bytesToSign);
        BigInteger r = signature[0];
        BigInteger s = signature[1];
        return new SignatureData(r.toString(16), s.toString(16));
    }

    public static String getPublicKeyFromPrivateKey(String privateKeyHex) {
        byte[] privateKey = DatatypeConverter.parseHexBinary(privateKeyHex);
        ECPoint curvePt = domain.getG().multiply(new BigInteger(1, privateKey));
        curvePt = curvePt.normalize();
        String x = curvePt.getXCoord().toBigInteger().toString(16);
        String y = curvePt.getYCoord().toBigInteger().toString(16);
        return paddingPublicKey(x, y);
    }

    private static String paddingPublicKey(String x, String y) {
        String paddingLetter = "0";

        StringBuilder xBuilder = new StringBuilder(x);
        while (xBuilder.length() < 64) {
            xBuilder.insert(0, paddingLetter);
        }
        x = xBuilder.toString();

        StringBuilder yBuilder = new StringBuilder(y);
        while (yBuilder.length() < 64) {
            yBuilder.insert(0, paddingLetter);
        }
        y = yBuilder.toString();
        return x + y;
    }

    public static boolean verifyByPublicKey(byte[] originalDataToVerify, String rHex, String sHex, PublicKey publicKey) {
        ECDSASigner signer = new ECDSASigner();
        signer.init(false, new ECPublicKeyParameters(((ECPublicKey) publicKey).getQ(), domain));
        BigInteger r = new BigInteger(rHex, 16);
        BigInteger s = new BigInteger(sHex, 16);
        return signer.verifySignature(originalDataToVerify, r, s);
    }

    public static boolean isAddressValid(Hash addressHash) {
        byte[] addressBytes = addressHash.getBytes();
        if (addressBytes.length != ADDRESS_SIZE_IN_BYTES) {
            return false;
        }
        Checksum checksum = new CRC32();
        byte[] addressWithoutCheckSum = Arrays.copyOfRange(addressBytes, 0, addressBytes.length - ADDRESS_CHECKSUM_SIZE_IN_BYTES);
        byte[] addressWithoutPadding = CryptoHelper.removeLeadingZerosFromAddress(addressWithoutCheckSum);

        byte[] addressCheckSum = Arrays.copyOfRange(addressBytes, addressBytes.length - ADDRESS_CHECKSUM_SIZE_IN_BYTES, addressBytes.length);
        checksum.update(addressWithoutPadding, 0, addressWithoutPadding.length);

        byte[] checksumValue = ByteBuffer.allocate(4).putInt((int) checksum.getValue()).array();
        return Arrays.equals(checksumValue, addressCheckSum);
    }

    private static byte[] getCrc32OfByteArray(byte[] array) {
        Checksum checksum = new CRC32();

        byte[] addressWithoutPadding = CryptoHelper.removeLeadingZerosFromAddress(array);
        checksum.update(addressWithoutPadding, 0, addressWithoutPadding.length);
        return ByteBuffer.allocate(4).putInt((int) checksum.getValue()).array();
    }


    public static Hash getAddressFromPrivateKey(String privateKey) {

        String publicKey = CryptoHelper.getPublicKeyFromPrivateKey(privateKey);
        byte[] crc32ToAdd = CryptoHelper.getCrc32OfByteArray(DatatypeConverter.parseHexBinary(publicKey));

        return new Hash(publicKey + DatatypeConverter.printHexBinary(crc32ToAdd));
    }

    public static Hash generatePrivateKey(String seed, Integer addressIndex) {

        byte[] seedInBytes = DatatypeConverter.parseHexBinary(seed);

        int byteBufferLength = 4 + seedInBytes.length;

        byte[] addressWithIndexInBytes = ByteBuffer.allocate(byteBufferLength).put(seedInBytes).putInt(addressIndex).array();
        return cryptoHash(addressWithIndexInBytes);
    }

    public static Hash generateAddress(String seed, Integer addressIndex) {
        Hash privateKey = generatePrivateKey(seed, addressIndex);
        return (getAddressFromPrivateKey(privateKey.toString()));
    }

    public static Hash cryptoHash(byte[] input) {
        return cryptoHash(input, 256);
    }

    public static Hash cryptoHash(byte[] input, int bit) {
        Keccak.DigestKeccak digest = new Keccak.DigestKeccak(bit);
        digest.update(input);
        return new Hash(digest.digest());
    }
}
