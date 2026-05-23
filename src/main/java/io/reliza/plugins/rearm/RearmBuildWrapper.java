package io.reliza.plugins.rearm;

import java.io.IOException;
import java.time.Instant;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildWrapperDescriptor;
import jenkins.tasks.SimpleBuildWrapper;
import rearm.java.client.RearmFlags;
import rearm.java.client.RearmFlags.RearmFlagsBuilder;
import rearm.java.client.RearmLibrary;
import rearm.java.client.responses.RearmRelease;
import rearm.java.client.responses.RearmVersion;

/**
 * Pipeline wrapper that negotiates a new release version with ReARM and
 * exports {@code VERSION}, {@code DOCKER_VERSION}, and {@code LATEST_COMMIT}
 * (suffixed when {@code envSuffix} is set) for downstream steps.
 *
 * <p>Component resolution: either {@code componentId}, or {@code vcsUri} +
 * {@code repoPath} when the FREEFORM API key carries org-WRITE scope. When
 * {@code createComponentIfMissing=true} ReARM will auto-create the component
 * on first call.
 */
public class RearmBuildWrapper extends SimpleBuildWrapper {
	private String componentId;
	private String uri;
	private String vcsUri;
	private String repoPath;
	private String vcsDisplayName;
	private Boolean createComponentIfMissing = false;
	private String createComponentName;
	private String createComponentVersionSchema;
	private String createComponentFeatureBranchVersionSchema;
	private Boolean jenkinsVersionMeta = false;
	private String customVersionMeta;
	private String customVersionModifier;
	private String commitHash;
	private Boolean rebuild = false;
	private Boolean onlyVersion = false;
	// Default lifecycle for the release created at version-mint time. PENDING
	// is the canonical "build started" state — same convention `rearm-actions`
	// uses in GHA. `addRearmRelease` later upgrades it to ASSEMBLED with the
	// full metadata, taking advantage of ReARM's PENDING→update path on
	// `addReleaseProgrammatic` (non-PENDING existing releases would dedup-fail).
	// Override by passing `lifecycle: '<state>'`; pair with `onlyVersion: 'true'`
	// to suppress release creation entirely.
	private String lifecycle = "PENDING";
	private Boolean getVersion = true;
	private String envSuffix = "";

	@DataBoundConstructor
	public RearmBuildWrapper() {
		super();
	}

	@DataBoundSetter public void setComponentId(String componentId) { this.componentId = componentId; }
	@DataBoundSetter public void setUri(String uri) { this.uri = uri; }
	@DataBoundSetter public void setVcsUri(String vcsUri) { this.vcsUri = vcsUri; }
	@DataBoundSetter public void setRepoPath(String repoPath) { this.repoPath = repoPath; }
	@DataBoundSetter public void setVcsDisplayName(String vcsDisplayName) { this.vcsDisplayName = vcsDisplayName; }
	@DataBoundSetter public void setCreateComponentIfMissing(String value) {
		this.createComponentIfMissing = "true".equalsIgnoreCase(value);
	}
	@DataBoundSetter public void setCreateComponentName(String name) { this.createComponentName = name; }
	@DataBoundSetter public void setCreateComponentVersionSchema(String s) { this.createComponentVersionSchema = s; }
	@DataBoundSetter public void setCreateComponentFeatureBranchVersionSchema(String s) {
		this.createComponentFeatureBranchVersionSchema = s;
	}
	@DataBoundSetter public void setJenkinsVersionMeta(String value) {
		this.jenkinsVersionMeta = "true".equalsIgnoreCase(value);
	}
	@DataBoundSetter public void setCustomVersionMeta(String value) { this.customVersionMeta = value; }
	@DataBoundSetter public void setCustomVersionModifier(String value) { this.customVersionModifier = value; }
	@DataBoundSetter public void setOnlyVersion(String value) {
		this.onlyVersion = "true".equalsIgnoreCase(value);
	}
	@DataBoundSetter public void setLifecycle(String lifecycle) {
		this.lifecycle = lifecycle;
	}
	@DataBoundSetter public void setCommitHash(String commitHash) {
		this.commitHash = commitHash;
	}
	@DataBoundSetter public void setRebuild(String value) {
		this.rebuild = "true".equalsIgnoreCase(value);
	}
	@DataBoundSetter public void setGetVersion(String value) {
		this.getVersion = !"false".equalsIgnoreCase(value);
	}
	@DataBoundSetter public void setEnvSuffix(String envSuffix) { this.envSuffix = "_" + envSuffix; }

	@Override
	public void setUp(Context context, Run<?, ?> build, FilePath workspace, Launcher launcher,
			TaskListener listener, EnvVars initialEnvironment) throws IOException, InterruptedException {
		if (!this.requiresWorkspace()) {
			this.setUp(context, build, listener, initialEnvironment);
			return;
		}
		listener.getLogger().println("setting up ReARM context wrapper");

		context.env("BUILD_START_TIME" + envSuffix, Instant.now().toString());

		String resolvedCommit = commitHash != null
				? commitHash
				: RearmHelpers.resolveEnvVar("GIT_COMMIT", envSuffix, initialEnvironment);

		RearmFlagsBuilder flagsBuilder = RearmFlags.builder()
				.apiKeyId(initialEnvironment.get("REARM_API_USR"))
				.apiKey(initialEnvironment.get("REARM_API_PSW"))
				.componentId(RearmHelpers.toUUID(componentId, listener))
				.branch(RearmHelpers.resolveEnvVar("GIT_BRANCH", envSuffix, initialEnvironment))
				.commitHash(resolvedCommit)
				.commitMessage(RearmHelpers.resolveEnvVar("COMMIT_MESSAGE", envSuffix, initialEnvironment))
				.commitList(RearmHelpers.resolveEnvVar("COMMIT_LIST", envSuffix, initialEnvironment))
				.modifier(customVersionModifier)
				.lifecycle(lifecycle)
				.rebuild(rebuild)
				.onlyVersion(onlyVersion);

		if (uri != null) flagsBuilder.baseUrl(uri);
		if (vcsUri != null) flagsBuilder.vcsUri(vcsUri);
		if (repoPath != null) flagsBuilder.repoPath(repoPath);
		if (vcsDisplayName != null) flagsBuilder.vcsDisplayName(vcsDisplayName);
		if (Boolean.TRUE.equals(createComponentIfMissing)) {
			flagsBuilder.createComponentIfMissing(true);
			if (createComponentName != null) flagsBuilder.createComponentName(createComponentName);
			if (createComponentVersionSchema != null) flagsBuilder.createComponentVersionSchema(createComponentVersionSchema);
			if (createComponentFeatureBranchVersionSchema != null) {
				flagsBuilder.createComponentFeatureBranchVersionSchema(createComponentFeatureBranchVersionSchema);
			}
		}
		if (customVersionMeta != null) {
			flagsBuilder.metadata(customVersionMeta);
		} else if (Boolean.TRUE.equals(jenkinsVersionMeta)) {
			flagsBuilder.metadata(RearmHelpers.resolveEnvVar("BUILD_NUMBER", envSuffix, initialEnvironment));
		}

		RearmLibrary library = new RearmLibrary(flagsBuilder.build());

		if (Boolean.TRUE.equals(getVersion)) {
			RearmVersion version = library.getVersion();
			if (version == null) {
				throw new RuntimeException("ReARM did not return a version — check API key permissions and component resolution");
			}
			listener.getLogger().println("ReARM version: " + version.getVersion());
			context.env("VERSION" + envSuffix, version.getVersion());
			if (version.getDockerTagSafeVersion() != null) {
				context.env("DOCKER_VERSION" + envSuffix, version.getDockerTagSafeVersion());
			}
			if (version.getLifecycle() != null) {
				context.env("RELEASE_LIFECYCLE" + envSuffix, version.getLifecycle());
			}
		}

		RearmRelease latest = library.getLatestRelease();
		if (latest != null && latest.getSourceCodeEntryDetails() != null
				&& latest.getSourceCodeEntryDetails().getCommit() != null) {
			context.env("LATEST_COMMIT" + envSuffix, latest.getSourceCodeEntryDetails().getCommit());
		}
		if (uri != null) context.env("URI" + envSuffix, uri);
		if (componentId != null) context.env("COMPONENT_ID" + envSuffix, componentId);
		if (vcsUri != null) context.env("VCS_URI" + envSuffix, vcsUri);
		if (repoPath != null) context.env("REPO_PATH" + envSuffix, repoPath);
	}

	@Symbol("withRearm")
	@Extension
	public static final class DescriptorImpl extends BuildWrapperDescriptor {
		public DescriptorImpl() {
			super(RearmBuildWrapper.class);
			load();
		}

		@Override
		public boolean isApplicable(final AbstractProject<?, ?> item) {
			return true;
		}
	}
}
