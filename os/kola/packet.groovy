#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
                    description: 'Credentials for signing GCS download URLs',
                    name: 'DOWNLOAD_CREDS',
                    required: true),
        string(name: 'DOWNLOAD_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where image files are downloaded'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl',
                    defaultValue: 'd67b5bde-d138-487a-9da3-0f5f5f157310',
                    description: 'Credentials to run hosts in PACKET_PROJECT',
                    name: 'PACKET_CREDS',
                    required: true),
        string(name: 'PACKET_PROJECT',
               defaultValue: '9da29e12-d97c-4d6e-b5aa-72174390d57a',
               description: 'The Packet project ID to run test machines'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
                    description: 'Credentials to upload iPXE scripts',
                    name: 'UPLOAD_CREDS',
                    required: true),
        string(name: 'UPLOAD_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where development files are uploaded'),
        string(name: 'VERSION',
               defaultValue: '',
               description: 'OS image version to use'),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

/* The kola step doesn't fail the job, so save the return code separately.  */
def rc = 0

node('amd64 && docker') {
    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'StatusBuildSelector', stable: false]])

        withCredentials([
            file(credentialsId: params.DOWNLOAD_CREDS, variable: 'GOOGLE_APPLICATION_CREDENTIALS'),
            string(credentialsId: params.PACKET_CREDS, variable: 'PACKET_API_KEY'),
            file(credentialsId: params.UPLOAD_CREDS, variable: 'UPLOAD_CREDS'),
        ]) {
            withEnv(["BOARD=amd64-usr",
                     "COREOS_VERSION=${params.VERSION}",
                     "DOWNLOAD_ROOT=${params.DOWNLOAD_ROOT}",
                     "PACKET_PROJECT=${params.PACKET_PROJECT}",
                     "UPLOAD_ROOT=${params.UPLOAD_ROOT}"]) {
                rc = sh returnStatus: true, script: '''#!/bin/bash -ex

rm -rf *.tap _kola_temp* url.txt
touch url.txt

ln -f "${GOOGLE_APPLICATION_CREDENTIALS}" key.json
trap 'rm -f key.json' EXIT

# Generate a signed URL for downloading and installing private OS images.
docker run --network=host --rm -i -v "${PWD}:/wd" -w /wd fedora \
    /bin/bash -exc 'dnf -y install python2-oauth2client && exec python > url.txt' << EOF
from oauth2client.service_account import ServiceAccountCredentials
from base64 import b64encode as b64
from time import time
from urllib import quote

creds = ServiceAccountCredentials.from_json_keyfile_name('key.json')
expires = time() + 2 * 60 * 60
path = '/${DOWNLOAD_ROOT#gs://}/boards/${BOARD}/${COREOS_VERSION}/coreos_production_packet_image.bin.bz2'
signature = creds.sign_blob("GET\\n\\n\\n%u\\n%s" % (expires, path))[1]

print "https://storage.googleapis.com%s?GoogleAccessId=%s&Expires=%u&Signature=%s" % (
    path, creds.service_account_email, expires, quote(b64(signature))
)
EOF

timeout --signal=SIGQUIT 2h bin/kola run \
    --board="${BOARD}" \
    --gce-json-key="${UPLOAD_CREDS}" \
    --packet-api-key="${PACKET_API_KEY}" \
    --packet-facility=ewr1 \
    --packet-image-url="$(<url.txt)" \
    --packet-project="${PACKET_PROJECT}" \
    --packet-storage-url="${UPLOAD_ROOT}/mantle/packet" \
    --parallel=4 \
    --platform=packet \
    --tapfile="${JOB_NAME##*/}.tap"
'''  /* Editor quote safety: ' */
            }
        }
    }

    stage('Post-build') {
        step([$class: 'TapPublisher',
              discardOldReports: false,
              enableSubtests: true,
              failIfNoResults: true,
              failedTestsMarkBuildAsFailure: true,
              flattenTapResult: false,
              includeCommentDiagnostics: true,
              outputTapToConsole: true,
              planRequired: true,
              showOnlyFailures: false,
              skipIfBuildNotOk: false,
              stripSingleParents: false,
              testResults: '*.tap',
              todoIsFailure: false,
              validateNumberOfTests: true,
              verbose: true])

        sh 'tar -cJf _kola_temp.tar.xz _kola_temp'
        archiveArtifacts '_kola_temp.tar.xz'
    }
}

/* Propagate the job status after publishing TAP results.  */
currentBuild.result = rc == 0 ? 'SUCCESS' : 'FAILURE'
