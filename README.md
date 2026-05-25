# Reliza / ReARM integration with Jenkins

## Plugin use

This plugin integrates with both [Reliza Hub](https://app.relizahub.com)
(`withReliza` / `addRelizaRelease`) and [ReARM](https://rearmhq.com)
(`withRearm` / `addRearmRelease`). The two are independent — pick the pair
that matches the backend you're targeting; they can also coexist in the same
Jenkinsfile when you're double-publishing during a migration.

More information on how to use Reliza Hub:
https://www.youtube.com/watch?v=yDlf5fMBGuI

## Setting up instance

For the plugin to interact with Reliza Hub you will need to set up credentials on your Jenkins instance.

### Acquiring Api Key and ID

Project API: Go to Reliza Hub -> project -> project you wish to integrate -> click on padlock -> record given api key and id

OR Org API (will require project ID): Go to Reliza Hub -> settings -> set org-wide read-write api key -> record given api key and id

Project ID (if using Org API): Go to Reliza Hub -> project -> project you wish to integrate -> click on wrench -> record UUID

### Storing in Jenkins

Go to your Jenkins instance -> Manage Jenkins -> Manage Credentials -> Domains: (global) -> Add Credentials

Set the credential type to "Username with password" and the scope to "Global".

Input your api key id into username and api key into password, then set identifying ID to "RELIZA_API", description can be anything.

### Jenkins Settings

In order for the plugin to link your build URL on Reliza Hub, the base URL of your Jenkins instance needs to be preset.

Go to your Jenkins instance -> Manage Jenkins -> Configure System -> Jenkins URL -> put in URL

## Available parameters
* *withReliza*: Wrapper will call Reliza Hub to get new version to be released. Version, docker safe version, and latest release commit can then be accessed from inside the wrapper using **env.VERSION**, **env.DOCKER_VERSION**, and **env.LATEST_COMMIT**
    * uri: Uri is defaulted to https://app.relizahub.com but this parameter can override it if necessary
    * projectId: Uuid of project required only if authenticating using an organization wide api
    * onlyVersion: If set to true then only version info will be obtained and release creation will be skipped
    * jenkinsVersionMeta: If set to true, will set the metadata flag to the Jenkins build id
    * customVersionMeta: Will set the metadata flag to a custom value and overrides jenkinsVersionMeta
    * customVersionModifier: Will set modifier flag to a custom value
    * getVersion: If set to false then wrapper will skip getting the new version to be released from Reliza Hub and will only set latest release commit to environment.
    * envSuffix: withReliza will now both send and expect environment variables with this suffix appended to it. Used for calling Reliza commands multiple times. e.g: pass envSuffix: "TEST" and commitMessage has to be set using env.COMMIT_MESSAGE_TEST and you can only access version through env.VERSION_TEST
* *addRelizaRelease*: This method will send release details to Reliza Hub
    * artId: Parameter to specify artifact id
    * artType: Parameter to specify artifact type
    * status: If needed, this parameter will set status and override previously set status environment variables
    * version: Parameter to specify custom version of new release instead of calling withReliza
    * useCommitList: If set to true, will disregard git commit, commit message, and commit time and will instead parse them using commit list
    * envSuffix: Identical functionality to envSuffix in withReliza
    * projectId/uri: If not calling withReliza, identical parameters can be used for this call
* Parameters set as environment variables which *withReliza* will read when set
    * GIT_BRANCH: Branch of commit, defaults to branch of commit which triggered build
    * BUILD_NUMBER: Build number, defaults to Jenkins build number
    * COMMIT_MESSAGE: Message of commit
    * COMMIT_LIST: Base64 encoded list of commits since latest release, below example shows how to format
* Parameters set as environment variables which *addRelizaRelease* will read when set
    * GIT_BRANCH: Branch of commit, defaults to branch which triggered build
    * GIT_URL: Url of repository, defaults to repository url of commit
    * GIT_COMMIT: Hash of commit, defaults to hash of commit which triggered build
    * COMMIT_TIME: Time of commit, defaults to time of commit which triggered build
    * BUILD_NUMBER: Build number, defaults to Jenkins build number
    * RUN_DISPLAY_URL: Direct link to build display, defaults to Jenkins build
    * BUILD_START_TIME: Time of start of build, defaults to time when withReliza is called
    * BUILD_END_TIME: Time of end of build, defaults to time when addRelizaRelease is called
    * STATUS: Sets build status to a choice of either complete or rejected
    * SHA_256: Sets sha256 of artifact
    * COMMIT_MESSAGE: Message of commit
    * COMMIT_LIST: Base64 encoded list of commits since latest release, below example shows how to format

withReliza is usually called to get the correct version from Reliza Hub which can then be used to call addRelizaRelease to create a new release. If needed the version parameter allows you to create a new release without calling withReliza.

Most parameters are automatically set using the initial build information as specified in the parameters, however they can all be overriden if necessary.

## Example Jenkinsfile/Pipeline usage

```groovy
pipeline {
    agent {
        kubernetes {
            yaml """
apiVersion: v1
kind: Pod
metadata:
  labels:
    run: jenkins-slave
spec:
  containers:
  - name: dind
    image: docker:19.03.12-dind
    command:
    - cat
    volumeMounts:
    - mountPath: /var/run/docker.sock
      name: dockersock
    tty: true
  volumes:
  - name: dockersock
    hostPath:
      path: /var/run/docker.sock
"""
        }
    }
    environment { RELIZA_API = credentials('RELIZA_API') }
    stages {
        stage('Build and Deploy') {
            steps {
                script {
                    env.COMMIT_TIME = sh(script: 'git log -1 --date=iso-strict --pretty="%ad"', returnStdout: true).trim()
                    env.COMMIT_MESSAGE = sh(script: 'git log -1 --pretty=%s', returnStdout: true).trim()
                    withReliza(jenkinsVersionMeta: 'true', customVersionModifier: 'Test') {
                        try {
                            if (env.LATEST_COMMIT) {
                                env.COMMIT_LIST = sh(script: 'git log $LATEST_COMMIT..$GIT_COMMIT --date=iso-strict --pretty="%H|||%ad|||%s|||%an|||%ae" | base64 -w 0', returnStdout: true).trim()
                            }
                            container('dind') {
                                sh '''
                                    docker build -t relizatest/throw .
                                    docker login -u USERNAME -p PASSWORD
                                    docker push relizatest/throw
                                '''
                                env.SHA_256 = sh(script: 'docker inspect -f \'{{range .RepoDigests}}{{.}}{{end}}\' relizatest/throw:latest | cut -f 2 -d\'@\'', returnStdout: true)
                            }
                        } catch (Exception e) {
                            env.STATUS = 'rejected'
                            echo 'FAILED BUILD: ' + e.toString()
                        }
                        addRelizaRelease(artId: "relizatest/throw", artType: "Docker")
                    }
                }
            }
        }
    }
}
```

## ReARM steps

The ReARM-flavoured steps mirror the Reliza Hub ones but speak the ReARM
GraphQL programmatic API (`getNewVersionProgrammatic`,
`addReleaseProgrammatic`, `getLatestReleaseProgrammatic`).

### Credentials

Mint a ReARM FREEFORM API key with at least `RESOURCE` + `LIFECYCLE_UPDATE`
permissions (ORGANIZATION scope, READ_WRITE) and store it as a Jenkins
"Username with password" credential under id `REARM_API` — the structured
`FREEFORM__<orgUuid>__ord__<keyOrder>` id goes in the username field and
the plaintext secret goes in the password field.

### `withRearm`

Negotiates a new version with ReARM and creates the release in `PENDING`
lifecycle (matching the `rearm-actions/initialize` GHA convention). Exports
the result as environment variables. The follow-up `addRearmRelease` updates
the same release in place — ReARM's `addReleaseProgrammatic` finishes a
PENDING release rather than rejecting on the `(component, version)` dedup
gate. Net effect: one row in ReARM, visible at PENDING immediately on
build start and bumped to ASSEMBLED at finish.

Parameters:

* `uri` — ReARM base URL (default `https://app.rearmhq.com`).
* `componentId` — UUID of the component, when the API key is component-scoped
  or you already know the UUID.
* `vcsUri` + `repoPath` — alternative to `componentId`; requires an
  organization-WRITE FREEFORM key. ReARM resolves the component from the
  `(vcsUri, repoPath)` pair, the same way `rearm-cli` does.
* `vcsDisplayName` — optional display name for the VCS repository row if
  ReARM has to create it.
* `createComponentIfMissing: 'true'` — auto-create the component when the
  `(vcsUri, repoPath)` lookup misses. Combine with `createComponentName`,
  `createComponentVersionSchema`, and
  `createComponentFeatureBranchVersionSchema` (feature-branch versioning is
  required by ReARM on component create — `Branch.Micro` is a safe default).
* `jenkinsVersionMeta: 'true'` — append the Jenkins build number as version
  metadata.
* `customVersionMeta` / `customVersionModifier` — explicit version
  metadata / modifier; `customVersionMeta` overrides `jenkinsVersionMeta`.
* `lifecycle` — override the default `PENDING` lifecycle at version-mint
  time. Set to `ASSEMBLED` for a one-shot "mint + finish in one call" mode
  if you don't plan to follow up with `addRearmRelease`.
* `onlyVersion: 'true'` — opt out of release creation entirely; only the
  version assignment is reserved. Useful when you intentionally want to
  defer release creation to `addRearmRelease` (or skip it altogether).
* `getVersion: 'false'` — skip the version negotiation entirely and only
  populate `LATEST_COMMIT`.
* `envSuffix` — suffix appended to every env var the step sets/reads, so
  multiple `withRearm` calls in the same pipeline don't stomp on each other.

Environment variables set:

* `VERSION` — the minted version.
* `DOCKER_VERSION` — `:` and `+` replaced for safe Docker tagging.
* `RELEASE_LIFECYCLE` — lifecycle as reported by ReARM (only when
  `onlyVersion=false`).
* `LATEST_COMMIT` — commit hash of the latest existing release (from
  `getLatestReleaseProgrammatic`).
* `URI`, `COMPONENT_ID`, `VCS_URI`, `REPO_PATH`, `BUILD_START_TIME` —
  context for downstream `addRearmRelease`.

### `addRearmRelease`

Creates the release on ReARM with metadata pulled from the wrapper's env vars
plus whatever is set on the step.

Parameters:

* `lifecycle` — explicit lifecycle to land on (default `ASSEMBLED`). Set to
  `REJECTED` for a failed build. The `STATUS` env var (`rejected` /
  `REJECTED`) is honoured for convenience when `lifecycle` isn't set.
* `status` — passed through to the release row for downstream tooling that
  reads it; doesn't drive the lifecycle decision.
* `artId`, `artType` — when both set together with `SHA_256` env var, the
  release will carry an artifact entry with the given digest, CI metadata,
  and build URL.
* `version` — overrides `env.VERSION` (useful when not using `withRearm`).
* `uri`, `componentId`, `vcsUri`, `repoPath` — override env-supplied
  context.
* `useCommitList: 'true'` — read the base64-encoded commit list from
  `COMMIT_LIST` instead of `GIT_COMMIT` + `COMMIT_MESSAGE` + `COMMIT_TIME`.
* `envSuffix` — same semantics as on `withRearm`.

### Example Jenkinsfile (ReARM)

```groovy
pipeline {
    agent any
    environment { REARM_API = credentials('REARM_API') }
    stages {
        stage('Capture commit metadata') {
            steps {
                script {
                    env.COMMIT_TIME    = sh(returnStdout: true,
                        script: "git log -1 --date=iso-strict --pretty='%ad'").trim()
                    env.COMMIT_MESSAGE = sh(returnStdout: true,
                        script: "git log -1 --pretty=%s").trim()
                }
            }
        }
        stage('Build + register release') {
            steps {
                withRearm(
                    uri:                                       'https://app.rearmhq.com',
                    vcsUri:                                    'https://github.com/acme/widget',
                    repoPath:                                  'service',
                    createComponentIfMissing:                  'true',
                    createComponentName:                       'acme widget service',
                    createComponentVersionSchema:              'semver',
                    createComponentFeatureBranchVersionSchema: 'Branch.Micro'
                ) {
                    script {
                        // withRearm already created the release in PENDING.
                        // Build the artefact, capture the digest, then finish:
                        env.SHA_256 = sh(returnStdout: true,
                            script: "docker inspect -f '{{(index .RepoDigests 0)}}' acme/widget:latest | cut -f2 -d@").trim()
                        addRearmRelease(artId: 'acme/widget', artType: 'Docker')
                    }
                }
            }
        }
    }
}
```

## Resources on pipelines and writing plugins
https://www.jenkins.io/doc/book/pipeline/syntax/  
https://www.jenkins.io/doc/developer/guides/  
https://wiki.jenkins.io/display/JENKINS/Extend+Jenkins  
https://wiki.jenkins.io/display/JENKINS/Plugin+tutorial  
https://softwareengineering.stackexchange.com/questions/64867/writing-jenkins-plugin-where-is-the-documentation/87913  
https://wiki.jenkins.io/display/JENKINS/Plugin+tutorial#Plugintutorial-CreatingaNewPlugin  
https://www.jenkins.io/doc/developer/plugin-development/pipeline-integration/  
https://github.com/jenkinsci/workflow-step-api-plugin/blob/master/README.md  
https://github.com/jenkinsci/workflow-basic-steps-plugin/blob/master/CORE-STEPS.md  
https://blog.codecentric.de/en/2012/08/tutorial-create-a-jenkins-plugin-to-integrate-jenkins-and-nexus-repository/  
http://javaadventure.blogspot.com/2008/04/writing-hudson-plugin-part-6-parsing.html  

## Version History

See the [changelog](https://github.com/jenkinsci/reliza-integration-plugin/blob/main/CHANGELOG.md)
