package moe.cdn.cweb.dht.security;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Optional;
import java.util.concurrent.ExecutionException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.inject.Inject;
import com.google.protobuf.Message;

import moe.cdn.cweb.SecurityProtos.Signature;
import moe.cdn.cweb.TorrentTrustProtos.SignedUser;
import moe.cdn.cweb.TorrentTrustProtos.User;
import moe.cdn.cweb.security.exceptions.UnsupportedAlgorithmException;
import moe.cdn.cweb.security.utils.Representations;
import moe.cdn.cweb.security.utils.SignatureUtils;

class SignatureValidationServiceImpl implements SignatureValidationService {

    private static final Logger logger = LogManager.getLogger();
    private final KeyLookupService userKeyService;

    @Inject
    public SignatureValidationServiceImpl(KeyLookupService userKeyService) {
        this.userKeyService = checkNotNull(userKeyService);
    }

    @Override
    public boolean validateSelfSigned(Signature signature, User user, Message message) {
        return validateSelfSigned(signature, user, message.toByteArray());
    }

    @Override
    public boolean validateSelfSigned(Signature signature, User user, byte[] data) {
        logger.debug("Validating signature {} owned by {} against <{}>...",
                Representations.asString(signature), Representations.asString(user),
                Representations.asString(data));
        switch (signature.getAlgorithm()) {
            case SHA_256_WITH_RSA:
                // Check matching public keys
                if (!user.getPublicKey().equals(signature.getPublicKey())) {
                    logger.info("User {} and Signature {} use different public keys!",
                            Representations.asString(user), Representations.asString(signature));
                    logger.info("User: {}\nSign: {}", Representations.asString(user.getPublicKey()),
                            Representations.asString(signature.getPublicKey()));
                    return false;
                }
                // Validate the signature
                return SignatureUtils.validateMessage(signature, data);

            case UNRECOGNIZED:
            default:
                throw new UnsupportedAlgorithmException();
        }
    }

    @Override
    public boolean validateAndCheckSignatureKeyInNetwork(Signature signature, byte[] data) {
        ListenableFuture<Optional<SignedUser>> futureOwner =
                userKeyService.findOwner(signature.getPublicKey());
        Optional<SignedUser> owner;
        try {
            owner = futureOwner.get();
        } catch (InterruptedException | ExecutionException e) {
            logger.catching(e);
            return false;
        }
        if (!owner.isPresent()) {
            logger.debug("Validation failed. Owner not found for signature: {}",
                    Representations.asString(signature));
            return false;
        }
        return validateSelfSigned(signature, owner.get().getUser(), data);
    }

    @Override
    public boolean validateAndCheckSignatureKeyInNetwork(Signature signature, Message message) {
        return validateAndCheckSignatureKeyInNetwork(signature, message.toByteArray());
    }

}
