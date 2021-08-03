import static com.pipeline.Collections.addWithoutDuplicates

def call(Map args = [:], Closure body) {
  withCredentials([usernamePassword(
    credentialsId: 'test-db-user',
    usernameVariable: 'dbUser',
    passwordVariable: 'dbPass'
  )]) {
    def rubyVersion = args.rubyVersion ?: '2.4'

    def defaultArgs = [
      name: 'pipeline-ruby-build',
      containers: [
        agentContainer(image: "salemove/jenkins-agent-ruby:${rubyVersion}"),
        passiveContainer(
          name: 'db',
          image: 'postgres:9.5.7-alpine',
          envVars: [
            envVar(key: 'POSTGRES_USER', value: dbUser),
            envVar(key: 'POSTGRES_PASSWORD', value: dbPass)
          ]
        ),
        passiveContainer(
          name: 'rabbitmq',
          image: 'rabbitmq:3.6.10-alpine'
        )
      ],
      volumes: [
        hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock'),
        hostPathVolume(hostPath: '/mnt/ruby-build/cache', mountPath: '/usr/local/bundle/cache')
      ]
    ]

    // For containers and volumes (list arguments), add the lists together, but
    // remove duplicates by name and mountPath respectively, giving precedence to
    // the user specified args.
    def finalContainers = addWithoutDuplicates((args.containers ?: []), defaultArgs.containers) { it.getArguments().name }
    def finalVolumes = addWithoutDuplicates((args.volumes ?: []), defaultArgs.volumes) { it.getArguments().mountPath }

    def finalArgs = defaultArgs << args << [
      containers: finalContainers,
      volumes: finalVolumes
    ]

    inPod(finalArgs) {
      body()
    }
  }
}
