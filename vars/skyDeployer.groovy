import groovy.transform.Field

import static com.pipeline.Collections.addWithoutDuplicates
import com.pipeline.Deployer
import com.pipeline.deploy.Github

@Field releaseProjectSubdir = '__release'

def wrapPodTemplate(Map args = [:]) {
  // Left for backwards compatibility
  args
}

def wrapProperties(providedProperties = []) {
  def isPRBuild = !!env.CHANGE_ID
  if (isPRBuild) {
    // Mark a special deploy status as pending, to indicate as soon as possible,
    // that this project now uses branch deploys and shouldn't be merged without
    // deploying.
    pullRequest.createStatus(
      status: 'pending',
      context: Github.deployStatusContext,
      description: 'The PR shouldn\'t be merged before it\'s deployed.',
      targetUrl: "${BUILD_URL}/console".toString()
    )
  }

  def isDeploy = Deployer.isDeploy(this)

  def tags = [
    "is_deploy=${isDeploy}",
    // Remove PR number or branch name suffix from the job name
    "project=${JOB_NAME.replaceFirst(/\/[^\/]+$/, '')}"
  ]

  if (isDeploy) {
    Deployer.validateTriggerArgs(this)

    tags.add("github_user=${Deployer.deployingUser(this)}")

    // Stop all previous builds that are still in progress
    while(currentBuild.rawBuild.getPreviousBuildInProgress() != null) {
      echo("Stopping ${currentBuild.rawBuild.getPreviousBuildInProgress()?.getAbsoluteUrl()}")
      currentBuild.rawBuild.getPreviousBuildInProgress()?.doStop()
      // Give the job some time to finish before trying again
      sleep(time: 10, unit: 'SECONDS')
    }
  }

  providedProperties + [
    pipelineTriggers([issueCommentTrigger(Deployer.triggerPattern)]),
    [
      $class: 'DatadogJobProperty',
      enableProperty: true,
      tagProperties: tags.join("\n")
    ]
  ]
}

def deployOnCommentTrigger(Map args) {
  if (Deployer.isDeploy(this)) {
    echo("Starting deploy")
    new Deployer(this, args).deploy()
  } else {
    echo("Build not triggered by !deploy comment. Pushing image to prepare for deploy.")
    new Deployer(this, args).pushImageForNextDeploy()
  }
}

def buildImageIfDoesNotExist(Map args, Closure body) {
  if (!args || !args.name) {
    error("'name' needs to be specified for buildImageIfDoesNotExist")
  }

  Deployer.buildImageIfDoesNotExist(this, args.name, body)
}

private def inToolbox(Map args = [:], Closure body) {
  def defaultArgs = [
    containers: [toolboxContainer()]
  ]
  def finalContainers = addWithoutDuplicates((args.containers ?: []), defaultArgs.containers) { it.getArguments().name }
  def finalArgs = defaultArgs << args << [containers: finalContainers]

  inPod(finalArgs) {
    checkout([
      $class: 'GitSCM',
      branches: [[name: 'master']],
      userRemoteConfigs: [[
        url: 'git@github.com:salemove/release.git',
        credentialsId: scm.userRemoteConfigs[0].credentialsId
      ]],
      extensions: [
        [$class: 'RelativeTargetDirectory', relativeTargetDir: releaseProjectSubdir],
        [$class: 'CloneOption', noTags: true, shallow: true]
      ]
    ])

    container('toolbox') {
      ansiColor('xterm') {
        body()
      }
    }
  }
}

def publishAssets(Map args) {
  def defaultArgs = [
    s3Bucket: 'libs.salemove.com',
    cacheMaxAge: 31536000 // seconds
  ]
  def finalArgs = defaultArgs << args

  def assetsStash = "assets-${UUID.randomUUID()}"
  stash(name: assetsStash, includes: "${finalArgs.folder}/**/*")

  withCredentials([
    string(variable: 'assumer', credentialsId: 'asset-publisher-assumer-iam-role'),
    string(variable: 'publisher', credentialsId: 'asset-publisher-iam-role')
  ]) {
    inToolbox(annotations: [podAnnotation(key: 'iam.amazonaws.com/role', value: assumer)]) {
      unstash(assetsStash)

      withEnv([
        "S3_BUCKET=${finalArgs.s3Bucket}",
        "DIST=${finalArgs.folder}",
        "CACHE_MAX_AGE=${finalArgs.cacheMaxAge}"
      ]) {
        sh("with-role ${publisher} ${releaseProjectSubdir}/publish_static_release")
      }
    }
  }
}

def deployAssetsVersion(Map args) {
  def integritiesStash = "integrities-${UUID.randomUUID()}"
  if (args.integritiesFile) {
    stash(name: integritiesStash, includes: args.integritiesFile)
  }

  inToolbox {
    lock('acceptance-environment') {
      def script = "${releaseProjectSubdir}/update_static_asset_versions"
      if (args.integritiesFile) {
        unstash(integritiesStash)
        sh("${script} ${args.version} ${args.integritiesFile}")
      } else {
        sh("${script} ${args.version}")
      }
    }
  }
}

def publishDocs(Map args) {
  def branchName = 'gitbooks'
  def docStash = 'doc-stash'
  stash(name: docStash, includes: args.source)

  stage('Publish docs') {
    inPod(containers: [
      interactiveContainer(name: 'toolbox', image: 'salemove/jenkins-toolbox:a99ffb7')
    ]) {
      checkout([
        $class: 'GitSCM',
        branches: [[name: branchName]],
        userRemoteConfigs: [[
          url: 'git@github.com:salemove/salemove.github.io.git',
          credentialsId: scm.userRemoteConfigs[0].credentialsId
        ]]
      ])

      container('toolbox') {
        sshagent([scm.userRemoteConfigs[0].credentialsId]) {
          sh("""\
            # Enable GitHub to link new commits to the sm-deployer user.
            git config user.name 'sm-deployer'
            git config user.email 'support@salemove.com'

            git checkout -b ${branchName}
          """.stripIndent())

          dir('tmp') {
            unstash(docStash)
          }

          sh("mkdir -p \$(dirname ${args.destination})")
          sh("mv tmp/${args.source} ${args.destination}")

          try {
            sh("""\
              git add ${args.destination}
              git commit -F- <<EOF && git push -u origin ${branchName}
              Publish documentation

              Automatically created using publishDocs().
              EOF
            """.stripIndent())
          } catch(e) {
            sh("echo 'Creating documentation commit failed. Probably nothing to commit.'")
          }
        }
      }
    }
  }
}
