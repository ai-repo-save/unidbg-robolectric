package io.github.aireposave.unidbg.runtime.worker;

import com.android.apksig.ApkVerifier;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ApkSigningMetadata {
    private static final Logger LOG = LoggerFactory.getLogger(ApkSigningMetadata.class);

    static ArrayNode signatures(Path source) throws Exception {
        var result = new ApkVerifier.Builder(source.toFile())
                .setMinCheckedPlatformVersion(30).setMaxCheckedPlatformVersion(30).build().verify();
        issues(source, "apk", -1, result.getErrors(), result.getWarnings());
        int index = 0;
        for (var signer : result.getV1SchemeSigners()) issues(source, "v1", index++, signer.getErrors(), signer.getWarnings());
        index = 0;
        for (var signer : result.getV1SchemeIgnoredSigners()) issues(source, "v1_ignored", index++, signer.getErrors(), signer.getWarnings());
        index = 0;
        for (var signer : result.getV2SchemeSigners()) issues(source, "v2", index++, signer.getErrors(), signer.getWarnings());
        index = 0;
        for (var signer : result.getV3SchemeSigners()) issues(source, "v3", index++, signer.getErrors(), signer.getWarnings());
        index = 0;
        for (var signer : result.getV31SchemeSigners()) issues(source, "v3.1", index++, signer.getErrors(), signer.getWarnings());
        index = 0;
        for (var signer : result.getV4SchemeSigners()) issues(source, "v4", index++, signer.getErrors(), signer.getWarnings());
        var stamp = result.getSourceStampInfo();
        if (stamp != null) issues(source, "source_stamp", -1, stamp.getErrors(), stamp.getWarnings());
        if (!result.isVerified()) throw new IllegalArgumentException("APK signature verification failed: " + source + ": " + result.getAllErrors());

        var certificates = result.getSignerCertificates();
        var lineage = result.getSigningCertificateLineage();
        // API 30 PackageParser.generatePackageInfo exposes the oldest signing identity for GET_SIGNATURES.
        if (lineage != null) certificates = List.of(lineage.getCertificatesInLineage().get(0));
        ArrayNode signatures = RequestData.JSON.createArrayNode();
        for (var certificate : certificates) signatures.add(Base64.getEncoder().encodeToString(certificate.getEncoded()));
        LOG.info("event=apk_signatures_verified apk={} v1={} v2={} v3={} v31={} getSignaturesCertificateCount={} lineage={}",
                source, result.isVerifiedUsingV1Scheme(), result.isVerifiedUsingV2Scheme(), result.isVerifiedUsingV3Scheme(),
                result.isVerifiedUsingV31Scheme(), signatures.size(), lineage != null);
        return signatures;
    }

    private static void issues(Path source, String scheme, int signer, List<ApkVerifier.IssueWithParams> errors,
            List<ApkVerifier.IssueWithParams> warnings) {
        for (var issue : errors) LOG.error("event=apk_signature_error apk={} scheme={} signer={} issue={}", source, scheme, signer, issue);
        for (var issue : warnings) LOG.warn("event=apk_signature_warning apk={} scheme={} signer={} issue={}", source, scheme, signer, issue);
    }

    private ApkSigningMetadata() {}
}
