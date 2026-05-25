package io.reliza.plugins.rearm;

import java.util.UUID;

import hudson.EnvVars;
import hudson.model.TaskListener;

/** Shared helpers for the ReARM Jenkins steps. */
final class RearmHelpers {
	private RearmHelpers() {}

	static UUID toUUID(String value, TaskListener listener) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			listener.getLogger().println(e);
			return null;
		}
	}

	static String resolveEnvVar(String envVar, String suffix, EnvVars envVars) {
		String suffixed = envVar + suffix;
		if (envVars.containsKey(suffixed)) {
			return envVars.get(suffixed);
		}
		if (envVars.containsKey(envVar)) {
			return envVars.get(envVar);
		}
		return null;
	}
}
