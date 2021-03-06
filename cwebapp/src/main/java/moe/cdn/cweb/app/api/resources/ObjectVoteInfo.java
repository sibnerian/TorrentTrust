package moe.cdn.cweb.app.api.resources;

import com.google.protobuf.ByteString;
import moe.cdn.cweb.CwebApiException;
import moe.cdn.cweb.SecurityProtos;
import moe.cdn.cweb.SecurityProtos.Hash;
import moe.cdn.cweb.SecurityProtos.Hash.HashAlgorithm;
import moe.cdn.cweb.TorrentTrustProtos;
import moe.cdn.cweb.TorrentTrustProtos.Vote;
import moe.cdn.cweb.TrustApi;
import moe.cdn.cweb.app.api.CwebApiEndPoint;
import moe.cdn.cweb.app.api.exceptions.NoSuchUserException;
import moe.cdn.cweb.app.dto.TrustRating;

import javax.ws.rs.*;
import java.security.InvalidKeyException;
import java.security.SignatureException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * @author davix
 */
@Path("object/{hash}")
@Produces("application/json")
public class ObjectVoteInfo extends CwebApiEndPoint {
    private static final String DEFAULT_CONTENT_PROPERTY = "appraisal";

    private static ByteString parseHash(String hash) throws BadRequestException {
        return ByteString.copyFrom(hash.getBytes());
    }

    @GET
    @Path("{algo}")
    public TrustRating getTrustRating(@PathParam("hash") String hash,
                                      @PathParam("algo") String algo)
            throws ExecutionException, InterruptedException, CwebApiException {
        Optional<TorrentTrustProtos.User> user = getCwebIdentityApi().getUserIdentity().get();
        TrustApi.TrustMetric trustMetric;
        try {
            trustMetric = TrustApi.TrustMetric.valueOf(algo);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown trust metric: " + algo, e);
        }
        if (user.isPresent()) {
            return new TrustRating(getCwebTrustApi().trustForObject(user.get(),
                    Vote.Assertion.newBuilder().setContentProperty(DEFAULT_CONTENT_PROPERTY)
                            .build(),
                    Hash.newBuilder().setHashValue(parseHash(hash))
                            .setAlgorithm(SecurityProtos.Hash.HashAlgorithm.TORRENT).build(),
                    trustMetric), trustMetric.name());
        }
        throw new NoSuchUserException("Current user does not exist in the network");
    }

    @GET
    @Produces({"text/plain"})
    public String getVoteHistory(@PathParam("hash") String hash) throws SignatureException,
            InvalidKeyException, ExecutionException, InterruptedException {
        return getCwebVoteApi()
                .getAllVotes(Hash.newBuilder().setAlgorithm(HashAlgorithm.TORRENT)
                        .setHashValue(parseHash(hash)).build())
                .get().stream().map(Vote::toString).collect(Collectors.toList()).toString();
    }

    @POST
    @Path("up")
    public void upVote(@PathParam("hash") String hash) throws SignatureException,
            InvalidKeyException, ExecutionException, InterruptedException {
        if (!getCwebVoteApi()
                .castVote(TorrentTrustProtos.Vote.newBuilder()
                        .addAssertion(TorrentTrustProtos.Vote.Assertion.newBuilder()
                                .setContentProperty(DEFAULT_CONTENT_PROPERTY)
                                .setRating(TorrentTrustProtos.Vote.Assertion.Rating.GOOD))
                        .setContentHash(SecurityProtos.Hash.newBuilder()
                                .setAlgorithm(SecurityProtos.Hash.HashAlgorithm.TORRENT)
                                .setHashValue(parseHash(hash)))
                        .setOwnerPublicKey(getCwebIdentities().getKeyPair().getPublicKey()).build())
                .get()) {
            throw new RuntimeException("Didn't work!");
        }
    }

    @POST
    @Path("down")
    public void downVote(@PathParam("hash") String hash) throws SignatureException,
            InvalidKeyException, ExecutionException, InterruptedException {
        if (!getCwebVoteApi()
                .castVote(TorrentTrustProtos.Vote.newBuilder()
                        .addAssertion(TorrentTrustProtos.Vote.Assertion.newBuilder()
                                .setContentProperty(DEFAULT_CONTENT_PROPERTY)
                                .setRating(TorrentTrustProtos.Vote.Assertion.Rating.BAD))
                        .setContentHash(SecurityProtos.Hash.newBuilder()
                                .setAlgorithm(SecurityProtos.Hash.HashAlgorithm.TORRENT)
                                .setHashValue(parseHash(hash)))
                        .setOwnerPublicKey(getCwebIdentities().getKeyPair().getPublicKey()).build())
                .get()) {
            throw new RuntimeException("Didn't work!");
        }
    }
}
