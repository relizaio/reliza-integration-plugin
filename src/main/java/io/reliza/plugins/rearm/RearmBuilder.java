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
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import jenkins.tasks.SimpleBuildStep;
import rearm.java.client.RearmFlags;
import rearm.java.client.RearmFlags.RearmFlagsBuilder;
import rearm.java.client.RearmLibrary;
import rearm.java.client.responses.RearmRelease;

/**
 * Pipeline step that records a release on ReARM. Pulls component / VCS context
 * from environment variables set by {@code withRearm}; explicit setters
 * override.
 */
public class RearmBuilder extends Builder implements SimpleBuildStep {
	String status;
	String lifecycle;
	String artId;
	String artType;
	String version;
	String uri;
	String componentId;
	String vcsUri;
	String repoPath;
	String deliverableId;
	String deliverableType;
	String deliverableDigest;
	String deliverablePurl;
	Boolean useCommitList = false;
	String envSuffix = "";

	@DataBoundConstructor
	public RearmBuilder() {}

	@DataBoundSetter public void setStatus(String status) { this.status = status; }
	@DataBoundSetter public void setLifecycle(String lifecycle) { this.lifecycle = lifecycle; }
	@DataBoundSetter public void setArtId(String artId) { this.artId = artId; }
	@DataBoundSetter public void setArtType(String artType) { this.artType = artType; }
	@DataBoundSetter public void setVersion(String version) { this.version = version; }
	@DataBoundSetter public void setComponentId(String componentId) { this.componentId = componentId; }
	@DataBoundSetter public void setUri(String uri) { this.uri = uri; }
	@DataBoundSetter public void setVcsUri(String vcsUri) { this.vcsUri = vcsUri; }
	@DataBoundSetter public void setRepoPath(String repoPath) { this.repoPath = repoPath; }
	@DataBoundSetter public void setDeliverableId(String v) { this.deliverableId = v; }
	@DataBoundSetter public void setDeliverableType(String v) { this.deliverableType = v; }
	@DataBoundSetter public void setDeliverableDigest(String v) { this.deliverableDigest = v; }
	@DataBoundSetter public void setDeliverablePurl(String v) { this.deliverablePurl = v; }
	@DataBoundSetter public void setUseCommitList(String value) {
		this.useCommitList = "true".equalsIgnoreCase(value);
	}
	@DataBoundSetter public void setEnvSuffix(String envSuffix) { this.envSuffix = "_" + envSuffix; }

	@Override
	public void perform(Run<?, ?> run, FilePath workspace, EnvVars envVars, Launcher launcher, TaskListener listener)
			throws InterruptedException, IOException {
		listener.getLogger().println("sending release metadata to ReARM");

		String resolvedComponentId = componentId != null ? componentId
				: RearmHelpers.resolveEnvVar("COMPONENT_ID", envSuffix, envVars);
		String resolvedVcsUri = vcsUri != null ? vcsUri
				: RearmHelpers.resolveEnvVar("VCS_URI", envSuffix, envVars);
		String resolvedRepoPath = repoPath != null ? repoPath
				: RearmHelpers.resolveEnvVar("REPO_PATH", envSuffix, envVars);

		RearmFlagsBuilder flagsBuilder = RearmFlags.builder()
				.apiKeyId(envVars.get("REARM_API_USR"))
				.apiKey(envVars.get("REARM_API_PSW"))
				.branch(RearmHelpers.resolveEnvVar("GIT_BRANCH", envSuffix, envVars))
				.version(RearmHelpers.resolveEnvVar("VERSION", envSuffix, envVars))
				.status(RearmHelpers.resolveEnvVar("STATUS", envSuffix, envVars))
				.componentId(RearmHelpers.toUUID(resolvedComponentId, listener))
				.commitList(RearmHelpers.resolveEnvVar("COMMIT_LIST", envSuffix, envVars))
				.vcsType("Git")
				.dateStart(RearmHelpers.resolveEnvVar("BUILD_START_TIME", envSuffix, envVars));

		String envVcsUri = RearmHelpers.resolveEnvVar("GIT_URL", envSuffix, envVars);
		if (resolvedVcsUri != null) {
			flagsBuilder.vcsUri(resolvedVcsUri);
		} else if (envVcsUri != null) {
			flagsBuilder.vcsUri(envVcsUri);
		}
		if (resolvedRepoPath != null) flagsBuilder.repoPath(resolvedRepoPath);

		boolean readCommitList = useCommitList;
		if (readCommitList) {
			String commitList = RearmHelpers.resolveEnvVar("COMMIT_LIST", envSuffix, envVars);
			readCommitList = commitList != null && !commitList.isEmpty();
		}
		if (!readCommitList) {
			flagsBuilder.commitHash(RearmHelpers.resolveEnvVar("GIT_COMMIT", envSuffix, envVars))
					.commitMessage(RearmHelpers.resolveEnvVar("COMMIT_MESSAGE", envSuffix, envVars))
					.commitAuthor(RearmHelpers.resolveEnvVar("COMMIT_AUTHOR", envSuffix, envVars))
					.commitEmail(RearmHelpers.resolveEnvVar("COMMIT_EMAIL", envSuffix, envVars))
					.dateActual(RearmHelpers.resolveEnvVar("COMMIT_TIME", envSuffix, envVars));
		}

		String buildEnd = RearmHelpers.resolveEnvVar("BUILD_END_TIME", envSuffix, envVars);
		flagsBuilder.dateEnd(buildEnd != null ? buildEnd : Instant.now().toString());

		String sha256 = envVars.get("SHA_256");
		if (artId != null && sha256 != null) {
			flagsBuilder.artId(artId)
					.artBuildId(RearmHelpers.resolveEnvVar("BUILD_NUMBER", envSuffix, envVars))
					.artBuildUri(RearmHelpers.resolveEnvVar("RUN_DISPLAY_URL", envSuffix, envVars))
					.artCiMeta("Jenkins")
					.artType(artType)
					.artDigests(sha256);
		}

		// Outbound deliverable (one per release) — typical for the
		// CI flow that ships a Docker image / file artifact alongside
		// the release. Mirrors rearm-cli's --odel* flags.
		if (deliverableId != null) {
			flagsBuilder.deliverableId(deliverableId);
			if (deliverableType != null) flagsBuilder.deliverableType(deliverableType);
			if (deliverableDigest != null) flagsBuilder.deliverableDigest(deliverableDigest);
			if (deliverablePurl != null) flagsBuilder.deliverablePurl(deliverablePurl);
			flagsBuilder.deliverableBuildId(RearmHelpers.resolveEnvVar("BUILD_NUMBER", envSuffix, envVars));
			flagsBuilder.deliverableBuildUri(RearmHelpers.resolveEnvVar("RUN_DISPLAY_URL", envSuffix, envVars));
			flagsBuilder.deliverableCiMeta("Jenkins");
		}

		String envUri = RearmHelpers.resolveEnvVar("URI", envSuffix, envVars);
		if (envUri != null) flagsBuilder.baseUrl(envUri);
		if (uri != null) flagsBuilder.baseUrl(uri);
		if (status != null) flagsBuilder.status(status);
		if (version != null) flagsBuilder.version(version);

		// Default the release lifecycle to ASSEMBLED so the typical flow
		// (withRearm starts the release in PENDING → addRearmRelease finishes
		// it) lands as expected. Explicit `lifecycle:` on the step wins;
		// a `STATUS` env var of "rejected" / "REJECTED" also wins (matches
		// the existing Reliza Hub-side convention of using STATUS).
		String resolvedLifecycle = "ASSEMBLED";
		if (lifecycle != null) {
			resolvedLifecycle = lifecycle.toUpperCase();
		} else {
			String envStatus = RearmHelpers.resolveEnvVar("STATUS", envSuffix, envVars);
			if (envStatus != null && envStatus.equalsIgnoreCase("rejected")) {
				resolvedLifecycle = "REJECTED";
			}
		}
		flagsBuilder.lifecycle(resolvedLifecycle);

		RearmLibrary library = new RearmLibrary(flagsBuilder.build());
		RearmRelease release = library.addRelease();
		if (release == null) {
			throw new RuntimeException("ReARM did not return a release — check logs for GraphQL errors");
		}
		listener.getLogger().println("ReARM release uuid: " + release.getUuid() + ", version: " + release.getVersion());
	}

	@Symbol("addRearmRelease")
	@Extension
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
		@Override
		public boolean isApplicable(Class<? extends AbstractProject> aClass) {
			return true;
		}
	}
}
