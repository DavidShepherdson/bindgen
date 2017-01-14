node{
    properties([])
    def jdkSuffix = ""
    def toolchain = ""
    // Mark the code checkout 'stage'....
    stage('Checkout') {
        if (env.JOB_NAME.contains('jdk7')) {
            toolchain = '-Djdk7'
        jdkSuffix = 'jdk7'
        } else if (env.JOB_NAME.contains('jdk8')) {
            toolchain = '-Djdk8'
        }
        setBuildStatus('building...', 'PENDING', jdkSuffix)
        checkout scm
    }

    // Mark the code build
    stage('Build') {
        def branchSuffix = ""
        def target = ""
        if (env.JOB_NAME.contains('deploy')) {
            target = 'deploy'
        } else {
            target = 'package'
        }
        if (env.BRANCH_NAME.contains('-') && env.BRANCH_NAME.split('-')[0] in ['feature', 'fix', 'hotfix']) {
            branchSuffix = env.BRANCH_NAME.substring(env.BRANCH_NAME.indexOf('-') + 1)
        }
        List versionSuffixes = []
        if (jdkSuffix != '') {
            versionSuffixes.add(jdkSuffix)
        }
        if (branchSuffix != '') {
            versionSuffixes.add(branchSuffix)
        }
        def versionSuffixDeclaration = ''
        if (!versionSuffixes.isEmpty()) {
            versionSuffixDeclaration = '-Dversion.suffix=' + versionSuffixes.join('-') + ' -Dversion.suffix.snapshot=true'
        }
        lock('bindgen') {
            dir('processor') {
                withMaven(jdk: 'JDK 1.8', maven: 'Maven 3.3.x') {
                    sh "mvn -Ddistribution=owsi-core-release ${versionSuffixDeclaration} ${toolchain} clean ${target}"
                    step([$class: 'JUnitResultArchiver', testResults: '**/target/surefire-reports/TEST-*.xml'])
                }
            }
            setBuildStatus('build complete!', 'SUCCESS', jdkSuffix)
        }
    }
}

void setBuildStatus(String message, String state, String variant) {
    def context = "ci/jenkins/build-status"
    if (variant) {
        context = "ci/jenkins/${variant}/build-status"
    }
    step([
        $class: "GitHubCommitStatusSetter",
        reposSource: [$class: "ManuallyEnteredRepositorySource", url: "https://github.com/openwide-java/bindgen"],
        contextSource: [$class: "ManuallyEnteredCommitContextSource", context: context],
        errorHandlers: [[$class: "ChangingBuildStatusErrorHandler", result: "UNSTABLE"]],
        statusResultSource: [ $class: "ConditionalStatusResultSource", results: [[$class: "AnyBuildResult", message: message, state: state]] ]
    ]);
}
