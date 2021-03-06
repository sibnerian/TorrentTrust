package moe.cdn.cweb.examples;

import java.security.InvalidKeyException;
import java.security.SignatureException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import moe.cdn.cweb.dht.annotations.KeyLookupDhtNodeController;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;

import moe.cdn.cweb.CwebApi;
import moe.cdn.cweb.CwebApiException;
import moe.cdn.cweb.CwebModuleService;
import moe.cdn.cweb.SecurityProtos;
import moe.cdn.cweb.TorrentTrustProtos.SignedUser;
import moe.cdn.cweb.TorrentTrustProtos.User;
import moe.cdn.cweb.TorrentTrustProtos.Vote;
import moe.cdn.cweb.dht.DhtModuleService;
import moe.cdn.cweb.dht.KeyEnvironment;
import moe.cdn.cweb.dht.ManagedPeer;
import moe.cdn.cweb.dht.annotations.PrimaryDhtNodeController;
import moe.cdn.cweb.dht.security.KeyLookupService;
import moe.cdn.cweb.security.CwebImportService;
import moe.cdn.cweb.security.utils.HashUtils;
import moe.cdn.cweb.security.utils.KeyUtils;
import moe.cdn.cweb.security.utils.Representations;
import moe.cdn.cweb.security.utils.SignatureUtils;

/**
 * Entry point for testing. Do not use in production.
 */
public class CwebExecutable {

    private static final Logger logger = LogManager.getLogger();

    /**
     * Usage: java moe.cdn.cweb.CwebExecutable [[id host_and_port] ...]
     *
     * @throws CwebApiException
     */
    public static void main(String[] args) throws ExecutionException, InterruptedException,
            CwebApiException, SignatureException, InvalidKeyException {
        Injector injector = Guice.createInjector(DhtModuleService.getInstance().getDhtModule(),
                CwebModuleService.getInstance().getCwebModule(), new ExampleModule(1717, args));

        CwebApi cwebApi = injector.getInstance(CwebApi.class);

        CwebImportService cwebImportService = injector.getInstance(CwebImportService.class);

        KeyEnvironment keyEnvironment = injector.getInstance(KeyEnvironment.class);
        SecurityProtos.KeyPair myKey = keyEnvironment.getKeyPair();
        SecurityProtos.KeyPair newKey = KeyUtils.generateKeyPair();

        User user = User.newBuilder().setHandle("name").setPublicKey(myKey.getPublicKey()).build();
        Vote voteFoo = Vote.newBuilder().setContentHash(HashUtils.hashOf("Foo"))
                .setOwnerPublicKey(myKey.getPublicKey()).build();
        Vote voteBar = Vote.newBuilder().setContentHash(HashUtils.hashOf("Bar"))
                .setOwnerPublicKey(myKey.getPublicKey()).build();
        Vote unknownVote = Vote.newBuilder().setContentHash(HashUtils.hashOf("Foo"))
                .setOwnerPublicKey(newKey.getPublicKey()).build();

        boolean b = cwebImportService.importUser(user).get();
        System.out.println("imported user <user>: " + b);

        KeyLookupService userKeyService = injector.getInstance(KeyLookupService.class);
        ListenableFuture<Optional<SignedUser>> owner =
                userKeyService.findOwner(user.getPublicKey());
        System.out.println(
                "owner of <user>'s public key: " + owner.get().map(Representations::asString));

        // Add vote
        b = cwebImportService.updateVote(voteFoo).get();
        System.out.println("imported vote <Foo>: " + b);
        System.out.println("votes for <Foo>: " + cwebApi.getVotes(HashUtils.hashOf("Foo")).stream()
                .map(Representations::asString).collect(Collectors.toList()));

        b = cwebImportService.updateVote(voteBar).get();
        System.out.println("imported vote <Bar>: " + b);
        System.out.println("votes for <Bar>: " + cwebApi.getVotes(HashUtils.hashOf("Bar")).stream()
                .map(Representations::asString).collect(Collectors.toList()));

        // Add alt-vote
        b = cwebImportService.importSignature(unknownVote,
                SignatureUtils.signMessageUnchecked(newKey, unknownVote)).get();
        System.out.println("imported foreign vote <Foo> (expect false): " + b);
        System.out.println("votes for <Foo>: " + cwebApi.getVotes(HashUtils.hashOf("Foo")).stream()
                .map(Representations::asString).collect(Collectors.toList()));

        ManagedPeer primaryNode =
                injector.getInstance(Key.get(ManagedPeer.class, PrimaryDhtNodeController.class));
        primaryNode.shutdown().get();
        ManagedPeer keyLookupNode =
                injector.getInstance(Key.get(ManagedPeer.class, KeyLookupDhtNodeController.class));
        keyLookupNode.shutdown().get();

        // Forcefully quit
        System.exit(0);
    }
}
