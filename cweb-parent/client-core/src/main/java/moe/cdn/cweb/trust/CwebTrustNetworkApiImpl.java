package moe.cdn.cweb.trust;

import static com.google.common.base.Preconditions.checkNotNull;

import java.security.InvalidKeyException;
import java.security.SignatureException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.base.Function;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.inject.Inject;

import moe.cdn.cweb.SecurityProtos.Hash;
import moe.cdn.cweb.SecurityProtos.Key;
import moe.cdn.cweb.SecurityProtos.KeyPair;
import moe.cdn.cweb.SecurityProtos.Signature;
import moe.cdn.cweb.TorrentTrustProtos.SignedUser;
import moe.cdn.cweb.TorrentTrustProtos.User;
import moe.cdn.cweb.TorrentTrustProtos.User.TrustAssertion;
import moe.cdn.cweb.TorrentTrustProtos.User.TrustAssertion.Trust;
import moe.cdn.cweb.dht.KeyEnvironment;
import moe.cdn.cweb.dht.security.CwebSignatureValidationService;
import moe.cdn.cweb.dht.security.KeyLookupService;
import moe.cdn.cweb.security.CwebImportService;
import moe.cdn.cweb.security.utils.KeyUtils;
import moe.cdn.cweb.security.utils.Representations;
import moe.cdn.cweb.security.utils.SignatureUtils;

class CwebTrustNetworkApiImpl implements CwebTrustNetworkApi, CwebIdentityApi {

    private static final Logger logger = LogManager.getLogger();
    private final KeyLookupService keyLookupService;
    private final CwebSignatureValidationService signatureValidationService;
    private final CwebImportService importService;
    private final KeyEnvironment keyEnvironment;

    @Inject
    public CwebTrustNetworkApiImpl(KeyLookupService userKeyService,
            CwebSignatureValidationService signatureValidationService,
            CwebImportService importService,
            KeyEnvironment keyEnvironment) {
        this.keyLookupService = checkNotNull(userKeyService);
        this.signatureValidationService = checkNotNull(signatureValidationService);
        this.importService = checkNotNull(importService);
        this.keyEnvironment = checkNotNull(keyEnvironment);
    }

    @Override
    public ListenableFuture<Collection<User>> getLocalTrustNetwork(User user) {
        logger.debug("Getting local trust network for {}", Representations.asString(user));
        LinkedList<ListenableFuture<Optional<User>>> maybeUsers = new LinkedList<>();
        for (TrustAssertion t : user.getTrustedList()) {
            if (!t.hasPublicKey() || !t.getTrustAssertion().equals(Trust.TRUSTED)) {
                logger.debug("Dropping {} ({}) from trust list. ", t.getPublicKey(), t.getTrustAssertion());
                continue;
            }
            maybeUsers.add(Futures.transform(keyLookupService.findOwner(t.getPublicKey()),
                    (Function<Optional<SignedUser>, Optional<User>>) maybeOwner -> maybeOwner.map(
                            o -> signatureValidationService.validateUser(o) ? o.getUser() : null)));
        }
        return Futures.transform(Futures.successfulAsList(maybeUsers),
                (Function<List<Optional<User>>, Collection<User>>) result -> result.stream()
                        .filter(Objects::nonNull).filter(Optional::isPresent).map(Optional::get)
                        .collect(Collectors.toList()));
    }

    @Override
    public ListenableFuture<Boolean> addUserAsTrusted(Key publicKey) {
        // TODO: We allow users to trust/ (make claims on trust) for users/keys
        // not in the network.
        logger.debug("Adding to trust network: {}", Representations.asString(publicKey));
        TrustAssertion trustAssertion = TrustAssertion.newBuilder().setPublicKey(publicKey)
                .setTrustAssertion(Trust.TRUSTED).build();
        return Futures.transform(
                keyLookupService.findOwner(keyEnvironment.getKeyPair().getPublicKey()),
                (AsyncFunction<Optional<SignedUser>, Boolean>) owner -> {
                    if (owner.isPresent()) {
                        // Handle existing trust assertions
                        List<TrustAssertion> cleanAssertionList =
                                owner.get().getUser().getTrustedList().stream()
                                        .filter(t -> !t.getPublicKey().equals(publicKey))
                                        .collect(Collectors.toList());
                        // Clear old assertions and add new one
                        return importService.importUser(owner.get().getUser().toBuilder()
                                .clearTrusted().addAllTrusted(cleanAssertionList)
                                .addTrusted(trustAssertion).build());
                    } else {
                        logger.info("Add trust failed for {}. Current user {} not found.",
                                Representations.asString(publicKey), Representations
                                        .asString(keyEnvironment.getKeyPair().getPublicKey()));
                        return Futures.immediateFuture(false);
                    }
                });
    }

    @Override
    public ListenableFuture<Boolean> removeUserAsTrusted(Key publicKey) {
        logger.debug("Removing from trust network: {}", Representations.asString(publicKey));
        TrustAssertion trustAssertion = TrustAssertion.newBuilder().setPublicKey(publicKey)
                .setTrustAssertion(Trust.NOT_TRUSTED).build();
        return Futures.transform(
                keyLookupService.findOwner(keyEnvironment.getKeyPair().getPublicKey()),
                (AsyncFunction<Optional<SignedUser>, Boolean>) owner -> {
                    if (owner.isPresent()) {
                        // Handle existing trust assertions
                        List<TrustAssertion> cleanAssertionList =
                                owner.get().getUser().getTrustedList().stream()
                                        .filter(t -> !t.getPublicKey().equals(publicKey))
                                        .collect(Collectors.toList());
                        return importService.importUser(owner.get().getUser().toBuilder()
                                .clearTrusted().addAllTrusted(cleanAssertionList)
                                .addTrusted(trustAssertion).build());
                    } else {
                        logger.info("Add trust failed for {}. Current user {} not found.",
                                Representations.asString(publicKey), Representations
                                        .asString(keyEnvironment.getKeyPair().getPublicKey()));
                        return Futures.immediateFuture(false);
                    }
                });
    }

    @Override
    public ListenableFuture<Boolean> addUserAsTrusted(Hash publicKey) {
        return Futures.transform(keyLookupService.findKey(publicKey),
                (AsyncFunction<Optional<Key>, Boolean>) key -> key.isPresent()
                        ? addUserAsTrusted(key.get()) : Futures.immediateFuture(false));
    }

    @Override
    public ListenableFuture<Boolean> removeUserAsTrusted(Hash publicKey) {
        return Futures.transform(keyLookupService.findKey(publicKey),
                (AsyncFunction<Optional<Key>, Boolean>) key -> key.isPresent()
                        ? removeUserAsTrusted(key.get()) : Futures.immediateFuture(false));
    }

    @Override
    public ListenableFuture<Optional<User>> getUserIdentity() {
        return getUserIdentity(keyEnvironment.getKeyPair().getPublicKey());
    }

    @Override
    public ListenableFuture<Optional<User>> getUserIdentity(Key publicKey) {
        return Futures.transform(keyLookupService.findOwner(publicKey),
                (Function<Optional<SignedUser>, Optional<User>>) o -> o.map(SignedUser::getUser));
    }

    @Override
    public ListenableFuture<Optional<KeyPair>> registerNewUserIdentity(String handle) {
        KeyPair keyPair = KeyUtils.generateKeyPair();
        User user =
                User.newBuilder().setHandle(handle).setPublicKey(keyPair.getPublicKey()).build();
        try {
            Signature signature = SignatureUtils.signMessage(keyPair, user);
            return Futures.transform(importService.importSignature(user, signature),
                    (Function<Boolean, Optional<KeyPair>>) ok -> ok ? Optional.of(keyPair)
                            : Optional.empty());
        } catch (InvalidKeyException | SignatureException e) {
            logger.catching(e);
            return Futures.immediateFailedFuture(e);
        }
    }

    @Override
    public ListenableFuture<Boolean> registerExistingUserIdentity(String handle, KeyPair keyPair) {
        User user =
                User.newBuilder().setHandle(handle).setPublicKey(keyPair.getPublicKey()).build();
        try {
            return importService.importSignature(user, SignatureUtils.signMessage(keyPair, user));
        } catch (InvalidKeyException | SignatureException e) {
            return Futures.immediateFuture(false);
        }
    }

}
