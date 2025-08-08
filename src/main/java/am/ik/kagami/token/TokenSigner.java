package am.ik.kagami.token;

import am.ik.kagami.KagamiProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

@Component
public class TokenSigner {

	private final JWSSigner signer;

	private final JWSVerifier verifier;

	public TokenSigner(KagamiProperties properties) {
		KagamiProperties.Jwt jwtProps = properties.jwt();
		this.signer = new RSASSASigner(jwtProps.privateKey());
		this.verifier = new RSASSAVerifier(jwtProps.publicKey());
		// validate the key pair
		JWTClaimsSet claimsSet = new JWTClaimsSet.Builder().subject("test").build();
		SignedJWT signedJWT = sign(claimsSet);
		try {
			if (!signedJWT.verify(this.verifier)) {
				throw new IllegalStateException("The pair of public key and private key is wrong.");
			}
		}
		catch (JOSEException e) {
			throw new IllegalStateException(e);
		}
	}

	public SignedJWT sign(JWTClaimsSet claimsSet) {
		JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build();
		SignedJWT signedJWT = new SignedJWT(header, claimsSet);
		try {
			signedJWT.sign(this.signer);
		}
		catch (JOSEException e) {
			throw new IllegalStateException(e);
		}
		return signedJWT;
	}

}