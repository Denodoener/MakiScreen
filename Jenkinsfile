pipeline {
  agent {
    docker {
      image 'eclipse-temurin:25-jdk'
    }
  }
  stages {
    stage('Build') {
      steps {
        sh './gradlew --no-daemon clean build'
      }
    }
    stage('Deliver') {
      steps {
        archiveArtifacts artifacts: 'build/libs/*.jar', fingerprint: true
      }
    }
  }
}
