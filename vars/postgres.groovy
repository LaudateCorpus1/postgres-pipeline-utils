@SuppressWarnings('MethodReturnTypeRequired')
def withDb(String dbName, String postgresVersion, Closure body) {
  @SuppressWarnings('VariableTypeRequired')
  def bodyResult = null
  String tempDir = pwd(temp: true) + "/${env.BUILD_ID}/postgres"

  // Here we create a Dockerfile based on the postgres version, but with a user mapping
  // that corresponds to the UID on the local machine. Without this, the postgres
  // container runs into all sorts of weird problems during initialization.
  // Also, while we're at it, we can define the POSTGRES_DB environment variable which
  // will instruct the container to create a database for us with the given name.
  String dockerfile = "${tempDir}/Dockerfile"
  String uid = sh(returnStdout: true, script: 'id -u').trim()
  String postgresUser = 'jenkins'
  writeFile(
    file: dockerfile,
    text: """
      FROM postgres:${postgresVersion}
      RUN useradd --uid ${uid} --user-group ${postgresUser}
      ENV POSTGRES_USER=${postgresUser}
      ENV POSTGRES_DB=${dbName}
      USER ${postgresUser}
      EXPOSE 5432
      ENTRYPOINT ["/docker-entrypoint.sh", "postgres"]
    """
  )

  String imageName = env.JOB_BASE_NAME.toLowerCase()
  @SuppressWarnings('VariableTypeRequired')
  def postgresImage = docker.build("${imageName}:${env.BUILD_ID}", "-f ${dockerfile} .")

  // Start the newly built container. The postgres data dir must be mapped to our
  // temporary data directory or else initdb runs into permission problems when trying
  // to chmod the data dir to our custom UID.
  dir("${tempDir}/data") {
    postgresImage.withRun("-p 5432:5432 -v ${pwd()}:/var/lib/postgresql/data") { c ->
      // Wait for the database to come up, for up to 30 seconds. Note that this command is
      // run from inside a new instance of the postgres container and linked to the
      // database container. By doing this, the postgres client does not need to be
      // installed on the build node. Note that when using docker.image.inside(), the
      // closure body is run instead of the entrypoint script.
      postgresImage.inside("--link ${c.id}:db") {
        retry(30) {
          sleep 1
          sh "pg_isready -h \$DB_PORT_5432_TCP_ADDR"
        }
      }

      // Now the database should be up and running, so we can execute the body from
      // outside the container.
      bodyResult = body.call()
    }

    deleteDir()
    return bodyResult
  }
}
