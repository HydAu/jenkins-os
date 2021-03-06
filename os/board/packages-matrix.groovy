#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        string(name: 'AWS_REGION',
               defaultValue: 'us-west-2',
               description: 'AWS region to use for AMIs and testing'),
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
         defaultValue: '1bb768fc-940d-4a95-95d0-27c1153e7fa0',
         description: 'AWS credentials list for AMI creation and releasing',
         name: 'AWS_RELEASE_CREDS',
         required: true],
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl',
         defaultValue: '6d37d17c-503e-4596-9a9b-1ab4373955a9',
         description: 'Credentials with permissions required by "kola run --platform=aws"',
         name: 'AWS_TEST_CREDS',
         required: true],
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
         defaultValue: '7ab88376-e794-4128-b644-41c83c89e76d',
         description: 'JSON credentials file for all Azure clouds used by plume',
         name: 'AZURE_CREDS',
         required: true],
        choice(name: 'BOARD',
               choices: "amd64-usr\narm64-usr",
               description: 'Target board to build'),
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey',
         defaultValue: '',
         description: 'Credential ID for SSH Git clone URLs',
         name: 'BUILDS_CLONE_CREDS',
         required: false],
        choice(name: 'COREOS_OFFICIAL',
               choices: "0\n1"),
        string(name: 'GROUP',
               defaultValue: 'developer',
               description: 'Which release group owns this build'),
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
         defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
         description: '''Credentials ID for a JSON file passed as the \
GOOGLE_APPLICATION_CREDENTIALS value for uploading development files to the \
Google Storage URL, requires write permission''',
         name: 'GS_DEVEL_CREDS',
         required: true],
        string(name: 'GS_DEVEL_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where development files are uploaded'),
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
         defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
         description: '''Credentials ID for a JSON file passed as the \
GOOGLE_APPLICATION_CREDENTIALS value for uploading release files to the \
Google Storage URL, requires write permission''',
         name: 'GS_RELEASE_CREDS',
         required: true],
        string(name: 'GS_RELEASE_DOWNLOAD_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where release files are downloaded'),
        string(name: 'GS_RELEASE_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where release files are uploaded'),
        string(name: 'MANIFEST_NAME',
               defaultValue: 'release.xml'),
        string(name: 'MANIFEST_TAG',
               defaultValue: ''),
        string(name: 'MANIFEST_URL',
               defaultValue: 'https://github.com/coreos/manifest-builds.git'),
        string(name: 'RELEASE_BASE',
               defaultValue: '',
               description: 'Use binary packages from this release version'),
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
         defaultValue: 'buildbot-official.EF4B4ED9.subkey.gpg',
         description: 'Credential ID for a GPG private key file',
         name: 'SIGNING_CREDS',
         required: true],
        string(name: 'SIGNING_USER',
               defaultValue: 'buildbot@coreos.com',
               description: 'E-mail address to identify the GPG key'),
        text(name: 'VERIFY_KEYRING',
             defaultValue: '',
             description: '''ASCII-armored keyring containing the public keys \
used to verify signed files and Git tags'''),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

node('coreos && amd64 && sudo') {
    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'StatusBuildSelector', stable: false]])

        writeFile file: 'verify.asc', text: params.VERIFY_KEYRING ?: ''

        sshagent(credentials: [params.BUILDS_CLONE_CREDS],
                 ignoreMissing: true) {
            withCredentials([
                [$class: 'FileBinding',
                 credentialsId: params.SIGNING_CREDS,
                 variable: 'GPG_SECRET_KEY_FILE'],
                [$class: 'FileBinding',
                 credentialsId: params.GS_DEVEL_CREDS,
                 variable: 'GOOGLE_APPLICATION_CREDENTIALS']
            ]) {
                withEnv(["BOARD=${params.BOARD}",
                         "COREOS_OFFICIAL=${params.COREOS_OFFICIAL}",
                         "DOWNLOAD_ROOT=${params.GS_DEVEL_ROOT}",
                         "MANIFEST_NAME=${params.MANIFEST_NAME}",
                         "MANIFEST_TAG=${params.MANIFEST_TAG}",
                         "MANIFEST_URL=${params.MANIFEST_URL}",
                         "RELEASE_BASE=${params.RELEASE_BASE}",
                         "SIGNING_USER=${params.SIGNING_USER}",
                         "UPLOAD_ROOT=${params.GS_DEVEL_ROOT}"]) {
                    sh '''#!/bin/bash -ex

# The build may not be started without a tag value.
[ -n "${MANIFEST_TAG}" ]

# Set up GPG for verifying tags.
export GNUPGHOME="${PWD}/.gnupg"
rm -rf "${GNUPGHOME}"
trap 'rm -rf "${GNUPGHOME}"' EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import verify.asc

bin/cork update \
    --create --downgrade-replace --verify --verify-signature --verbose \
    --manifest-branch "refs/tags/${MANIFEST_TAG}" \
    --manifest-name "${MANIFEST_NAME}" \
    --manifest-url "${MANIFEST_URL}" \
    -- --toolchain_boards="${BOARD}"

# Run branch-specific build commands from the scripts repository.
. src/scripts/jenkins/packages.sh
'''
                }
            }
        }
    }

    stage('Post-build') {
        fingerprint "chroot/build/${params.BOARD}/var/lib/portage/pkgs/*/*.tbz2,chroot/var/lib/portage/pkgs/*/*.tbz2"
    }
}

stage('Downstream') {
    build job: 'image-matrix', parameters: [
        string(name: 'AWS_REGION', value: params.AWS_REGION),
        [$class: 'CredentialsParameterValue', name: 'AWS_RELEASE_CREDS', value: params.AWS_RELEASE_CREDS],
        [$class: 'CredentialsParameterValue', name: 'AWS_TEST_CREDS', value: params.AWS_TEST_CREDS],
        [$class: 'CredentialsParameterValue', name: 'AZURE_CREDS', value: params.AZURE_CREDS],
        string(name: 'BOARD', value: params.BOARD),
        [$class: 'CredentialsParameterValue', name: 'BUILDS_CLONE_CREDS', value: params.BUILDS_CLONE_CREDS],
        string(name: 'COREOS_OFFICIAL', value: params.COREOS_OFFICIAL),
        string(name: 'GROUP', value: params.GROUP),
        [$class: 'CredentialsParameterValue', name: 'GS_DEVEL_CREDS', value: params.GS_DEVEL_CREDS],
        string(name: 'GS_DEVEL_ROOT', value: params.GS_DEVEL_ROOT),
        [$class: 'CredentialsParameterValue', name: 'GS_RELEASE_CREDS', value: params.GS_RELEASE_CREDS],
        string(name: 'GS_RELEASE_DOWNLOAD_ROOT', value: params.GS_RELEASE_DOWNLOAD_ROOT),
        string(name: 'GS_RELEASE_ROOT', value: params.GS_RELEASE_ROOT),
        string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
        string(name: 'MANIFEST_TAG', value: params.MANIFEST_TAG),
        string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
        [$class: 'CredentialsParameterValue', name: 'SIGNING_CREDS', value: params.SIGNING_CREDS],
        string(name: 'SIGNING_USER', value: params.SIGNING_USER),
        text(name: 'VERIFY_KEYRING', value: params.VERIFY_KEYRING),
        string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
    ]
}
