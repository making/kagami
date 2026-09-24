package am.ik.kagami.rbac;

import java.util.Map;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.rbac.RbacBuiltins;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binding tests for {@link KagamiProperties.Rbac}: built-in group defaults, empty group
 * values and bracket-notation keys must survive relaxed binding.
 */
class RbacBindingTest {

	private KagamiProperties.Rbac bind(Map<String, String> properties) {
		MapConfigurationPropertySource source = new MapConfigurationPropertySource(properties);
		return new Binder(source).bind("kagami.rbac", Bindable.of(KagamiProperties.Rbac.class))
			.orElse(new KagamiProperties.Rbac(RbacBuiltins.ADMINISTRATORS_GROUP, Map.of(),
					new KagamiProperties.Mappings(Map.of(), Map.of())));
	}

	@Test
	void bindsBuiltInGroupsWhenNothingIsConfigured() {
		KagamiProperties.Rbac rbac = bind(Map.of());
		assertThat(rbac.defaultGroup()).isEqualTo(RbacBuiltins.ADMINISTRATORS_GROUP);
		assertThat(rbac.groups()).containsEntry(RbacBuiltins.ADMINISTRATORS_GROUP, RbacBuiltins.ALLOWED_AUTHORITIES);
		assertThat(rbac.mappings().user()).isEmpty();
		assertThat(rbac.mappings().groups()).isEmpty();
	}

	@Test
	void emptyGroupValueBindsToEmptyAuthorityList() {
		KagamiProperties.Rbac rbac = bind(Map.of("kagami.rbac.groups.no-access", ""));
		// An empty value must become an empty list, not a one-element list with ""
		assertThat(rbac.groups()).containsEntry("no-access", java.util.List.of());
	}

	@Test
	void bracketNotationKeysSurviveRelaxedBinding() {
		KagamiProperties.Rbac rbac = bind(
				Map.of("kagami.rbac.mappings.user[taro@example.com]", "editors", "kagami.rbac.mappings.user[taro.test]",
						"viewers", "kagami.rbac.mappings.groups[my-team-admins]", "administrators"));
		assertThat(rbac.mappings().user()).containsEntry("taro@example.com", java.util.List.of("editors"));
		assertThat(rbac.mappings().user()).containsEntry("taro.test", java.util.List.of("viewers"));
		assertThat(rbac.mappings().groups()).containsEntry("my-team-admins", java.util.List.of("administrators"));
	}

	@Test
	void configuredGroupOverridesBuiltInGroup() {
		KagamiProperties.Rbac rbac = bind(Map.of("kagami.rbac.groups.viewers", "artifacts:read,artifacts:delete",
				"kagami.rbac.groups.extra", "artifacts:read"));
		assertThat(rbac.groups()).containsEntry(RbacBuiltins.VIEWERS_GROUP, RbacBuiltins.ALLOWED_AUTHORITIES);
		assertThat(rbac.groups()).containsEntry("extra", java.util.List.of("artifacts:read"));
	}

	@Test
	void defaultGroupIsConfigurable() {
		KagamiProperties.Rbac rbac = bind(
				Map.of("kagami.rbac.default-group", "no-access", "kagami.rbac.groups.no-access", ""));
		assertThat(rbac.defaultGroup()).isEqualTo("no-access");
	}

}
