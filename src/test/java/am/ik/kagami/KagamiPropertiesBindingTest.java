package am.ik.kagami;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import am.ik.kagami.rbac.RbacBuiltins;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binding tests for {@link KagamiProperties} and its nested records.
 * <p>
 * Spring Boot's value-object binder only binds a nested record when its canonical
 * constructor is the sole constructor. An extra constructor makes the binder fall back to
 * the bean binder, which finds no setters on a record and silently keeps the
 * {@code @DefaultValue}s, so these tests assert configured values rather than defaults.
 */
class KagamiPropertiesBindingTest {

	private static <T> T bind(String prefix, Class<T> type, Map<String, String> properties) {
		MapConfigurationPropertySource source = new MapConfigurationPropertySource(properties);
		return new Binder(source).bind(prefix, Bindable.of(type))
			.orElseThrow(() -> new IllegalStateException("no value bound for " + prefix));
	}

	private static <T> T bindOrCreate(String prefix, Class<T> type) {
		return new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate(prefix, Bindable.of(type));
	}

	private KagamiProperties.Rbac bindRbac(Map<String, String> properties) {
		return bind("kagami.rbac", KagamiProperties.Rbac.class, properties);
	}

	@Test
	void bindsBuiltInGroupsWhenNothingIsConfigured() {
		KagamiProperties.Rbac rbac = bindOrCreate("kagami.rbac", KagamiProperties.Rbac.class);
		assertThat(rbac.defaultGroup()).isEqualTo(RbacBuiltins.DEFAULT_GROUP);
		assertThat(rbac.groups()).containsEntry(RbacBuiltins.EDITORS_GROUP,
				List.of("artifacts:read", "artifacts:delete"));
		assertThat(rbac.groups()).containsEntry(RbacBuiltins.ADMINISTRATORS_GROUP, RbacBuiltins.ALLOWED_AUTHORITIES);
		assertThat(rbac.mappings().users()).isEmpty();
		assertThat(rbac.mappings().groups()).isEmpty();
	}

	@Test
	void emptyGroupValueBindsToEmptyAuthorityList() {
		KagamiProperties.Rbac rbac = bindRbac(Map.of("kagami.rbac.groups.no-access", ""));
		// An empty value must become an empty list, not a one-element list with ""
		assertThat(rbac.groups()).containsEntry("no-access", java.util.List.of());
	}

	@Test
	void bracketNotationKeysSurviveRelaxedBinding() {
		KagamiProperties.Rbac rbac = bindRbac(Map.of("kagami.rbac.mappings.users[taro@example.com]", "editors",
				"kagami.rbac.mappings.users[taro.test]", "viewers", "kagami.rbac.mappings.groups[my-team-admins]",
				"administrators"));
		assertThat(rbac.mappings().users()).containsEntry("taro@example.com", java.util.List.of("editors"));
		assertThat(rbac.mappings().users()).containsEntry("taro.test", java.util.List.of("viewers"));
		assertThat(rbac.mappings().groups()).containsEntry("my-team-admins", java.util.List.of("administrators"));
	}

	@Test
	void configuredGroupOverridesBuiltInGroup() {
		KagamiProperties.Rbac rbac = bindRbac(Map.of("kagami.rbac.groups.viewers", "artifacts:read,artifacts:delete",
				"kagami.rbac.groups.extra", "artifacts:read"));
		assertThat(rbac.groups()).containsEntry(RbacBuiltins.VIEWERS_GROUP,
				List.of("artifacts:read", "artifacts:delete"));
		assertThat(rbac.groups()).containsEntry("extra", java.util.List.of("artifacts:read"));
	}

	@Test
	void defaultGroupIsConfigurable() {
		KagamiProperties.Rbac rbac = bindRbac(
				Map.of("kagami.rbac.default-group", "no-access", "kagami.rbac.groups.no-access", ""));
		assertThat(rbac.defaultGroup()).isEqualTo("no-access");
	}

	@Test
	void upstreamSettingsBind() {
		KagamiProperties.UpstreamConfig upstream = bind("kagami.upstream", KagamiProperties.UpstreamConfig.class,
				Map.of("kagami.upstream.connect-timeout", "7s", "kagami.upstream.request-timeout", "45s",
						"kagami.upstream.failure-threshold", "9", "kagami.upstream.open-duration", "1m",
						"kagami.upstream.tarball-hosts", "registry.npmjs.org,files.example.com"));
		assertThat(upstream.connectTimeout()).isEqualTo(Duration.ofSeconds(7));
		assertThat(upstream.requestTimeout()).isEqualTo(Duration.ofSeconds(45));
		assertThat(upstream.failureThreshold()).isEqualTo(9);
		assertThat(upstream.openDuration()).isEqualTo(Duration.ofMinutes(1));
		assertThat(upstream.tarballHosts()).containsExactly("registry.npmjs.org", "files.example.com");
	}

	@Test
	void upstreamSettingsHaveDefaults() {
		KagamiProperties.UpstreamConfig upstream = bindOrCreate("kagami.upstream",
				KagamiProperties.UpstreamConfig.class);
		assertThat(upstream.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
		assertThat(upstream.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
		assertThat(upstream.failureThreshold()).isEqualTo(5);
		assertThat(upstream.openDuration()).isEqualTo(Duration.ofSeconds(30));
		assertThat(upstream.tarballHosts()).isEmpty();
	}

	@Test
	void metadataCacheSettingsBind() {
		KagamiProperties.MetadataCacheConfig metadataCache = bind("kagami.metadata-cache",
				KagamiProperties.MetadataCacheConfig.class,
				Map.of("kagami.metadata-cache.ttl", "2m", "kagami.metadata-cache.stale-ttl", "3h",
						"kagami.metadata-cache.max-entries", "32", "kagami.metadata-cache.max-body-size", "1MB"));
		assertThat(metadataCache.ttl()).isEqualTo(Duration.ofMinutes(2));
		assertThat(metadataCache.staleTtl()).isEqualTo(Duration.ofHours(3));
		assertThat(metadataCache.maxEntries()).isEqualTo(32);
		assertThat(metadataCache.maxBodySize().toBytes()).isEqualTo(1024 * 1024);
	}

}
