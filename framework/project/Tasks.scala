import sbt._
import Keys._
import sbt.File

object Generators {
  // Generates a scala file that contains the play version for use at runtime.
  def PlayVersion(dir: File): Seq[File] = {
      val file = dir / "PlayVersion.scala"
      IO.write(file,
        """|package play.core
            |
            |object PlayVersion {
            |    val current = "%s"
            |    val scalaVersion = "%s"
            |}
          """.stripMargin.format(BuildSettings.buildVersion, BuildSettings.buildScalaVersion))
      Seq(file)
  }
}

object Tasks {

  // ----- Generate Distribution
  lazy val generateDist = TaskKey[Unit]("create-dist")
  val generateDistTask: Setting[_] = 
    generateDist <<= (/*generateAPIDocs,*/ RepositoryBuilder.localRepoCreated in PlayBuild.RepositoryProject, baseDirectory in ThisBuild, target, version) map {
      (/*_,*/ repo, bd, t, v) =>
        generateDistribution(repo, bd, t, v)
    }

  def generateDistribution(repo: File, bd: File, target: File, version: String): File = {
    // Go down to the play checkout and get rid of the dumbness.
    val playBase = bd.getParentFile
    // Assert if we have the right directory...
    assert(playBase / ".git" isDirectory, "%s is not play's home directory" format(playBase))
    
    val coreFiles = Seq("play", "play.bat", "README.md", "CONTRIBUTING.md") map (playBase /)
    
    val dist = target / "dist" / ("play-" + version)
    IO.createDirectory(dist)
    IO.createDirectory(dist / "repository")

    // Basically the same as IO.copy, but maintains executable permissions
    def copyMaintainPerms(sources: Traversable[(File,File)]) {
      sources.foreach(Function.tupled { (from, to) =>
        if (!to.exists || from.lastModified > to.lastModified) {
          if(from.isDirectory) {
            IO.createDirectory(to)
          } else {
            IO.createDirectory(to.getParentFile)
            IO.copyFile(from, to)
            if (from.canExecute) {
              to.setExecutable(true)
            }
          }
        }
      })
    }
    
    // First, let's do the dangerously fun copy of our current sources.
    def copyDist(dir: String): Unit = {
      val code = playBase / dir
      val distCode = dist / dir
      object files extends Traversable[File] {

        // Paths to exclude
        val badPaths = Seq("target", "bin", "logs", "tmp", "integrationtest/dist", "sbt/boot", "project/project")
          .map(_.split('/').reverse)
        // Function that matches a file against a path
        def matches(file: File, path: Seq[String]): Boolean = path match {
          case Seq(head) if (head == file.getName) => true
          case Seq(head, tail @ _*) if (head == file.getName) => matches(file.getParentFile, tail)
          case _ => false
        }
        // Whether the file should be excluded from the dist
        def exclude(file: File) = {
          badPaths.exists(p => matches(file, p)) ||
          (file.getName.startsWith(".") && file.getName != ".gitignore")
        }

        def foreach[U](f: File => U): Unit = {
          @annotation.tailrec
          def drive(files: Seq[File]): Unit = files match {
            // Don't go down any of the "bad" directory paths.
            case Seq(head, tail @ _*) if exclude(head) => drive(tail)
            case Seq(head, tail @ _*) =>
              f(head)
              drive(IO.listFiles(head) ++ tail)
            case Nil => ()
          }
          drive(IO.listFiles(code))
        }
      }

      val toCopy =
        for {
          (file, name) <- files.toSeq x relativeTo(code)
        } yield file -> (distCode / name)
      copyMaintainPerms(toCopy)
    }
    copyDist("framework")
    copyDist("samples")
    copyDist("documentation")

    // Copy the core files
    copyMaintainPerms(coreFiles map (f => f -> (dist / f.getName)))
    IO.copyDirectory(repo, dist / "repository" / "local", true, false)

    // Update versions
    def updatePlayVersion(file: File) {
      val contents = IO.read(file)
      contents.replaceAll("PLAY_VERSION=.*", "PLAY_VERSION=" + version)
      IO.write(file, contents)
    }
    updatePlayVersion(dist / "framework" / "build")
    updatePlayVersion(dist / "framework" / "build.bat")

    // play.boot.properties is a bit special since there are two version: lines, only one of which we should update
    val playBootProperties = dist / "framework" / "sbt" / "play.boot.properties"
    val lines = IO.readLines(playBootProperties)
    val (preapp, postapp) = lines.span(_.trim != "[app]")
    val (preversion, postversion) = postapp.span(_.trim.startsWith("version:"))
    val versionLine = postversion.head.replaceAll("version: .*", "version: " + version)
    IO.writeLines(playBootProperties, (preapp ++ preversion :+ versionLine) ++ postversion.tail)

    ZipHelper.zipNative(dist.getParentFile, target / ("play-" + version + ".zip"))

    target
  }
  // ----- Generate API docs

  lazy val generateAPIDocs = TaskKey[Unit]("api-docs")
  val generateAPIDocsTask = TaskKey[Unit]("api-docs") <<= (dependencyClasspath in Test, compilers, streams, baseDirectory, scalaBinaryVersion) map {
    (classpath, cs, s, base, sbv) =>

      val allJars = (file("src") ** "*.jar").get

      IO.delete(file("../documentation/api"))

      // Scaladoc
      val sourceFiles =
        (file("src/play/src/main/scala/play/api") ** "*.scala").get ++
          (file("src/iteratees/src/main/scala") ** "*.scala").get ++
          (file("src/play-test/src/main/scala") ** "*.scala").get ++
          (file("src/play/src/main/scala/views") ** "*.scala").get ++
          (file("src/anorm/src/main/scala") ** "*.scala").get ++
          (file("src/play-filters-helpers/src/main/scala") ** "*.scala").get ++
          (file("src/play-jdbc/src/main/scala") ** "*.scala").get ++
          (file("src/play/target/scala-" + sbv + "/src_managed/main/views/html/helper") ** "*.scala").get ++
          (file("src/templates/src/main/scala") ** "*.scala").get
      val options = Seq("-sourcepath", base.getAbsolutePath, "-doc-source-url", "https://github.com/playframework/Play20/tree/" + BuildSettings.buildVersion + "/framework€{FILE_PATH}.scala")
      new Scaladoc(10, cs.scalac)("Play " + BuildSettings.buildVersion + " Scala API", sourceFiles, classpath.map(_.data) ++ allJars, file("../documentation/api/scala"), options, s.log)

      // Javadoc
      val javaSources = Seq(
        file("src/play/src/main/java"),
        file("src/play-test/src/main/java"),
        file("src/play-java/src/main/java"),
        file("src/play-java-ebean/src/main/java"),
        file("src/play-java-jdbc/src/main/java"),
        file("src/play-java-jpa/src/main/java")).mkString(":")
      val javaApiTarget = file("../documentation/api/java")
      val javaClasspath = classpath.map(_.data).mkString(":")
      """javadoc -windowtitle playframework -doctitle Play&nbsp;""" + BuildSettings.buildVersion + """&nbsp;Java&nbsp;API  -sourcepath %s -d %s -subpackages play -exclude play.api:play.core -classpath %s""".format(javaSources, javaApiTarget, javaClasspath) ! s.log

  }

  // ----- Compile templates

  lazy val ScalaTemplates = {
    (classpath: Seq[Attributed[File]], templateEngine: File, sourceDirectory: File, generatedDir: File, streams: sbt.std.TaskStreams[sbt.Project.ScopedKey[_]]) =>
      val classloader = new java.net.URLClassLoader(classpath.map(_.data.toURI.toURL).toArray, this.getClass.getClassLoader)
      val compiler = classloader.loadClass("play.templates.ScalaTemplateCompiler")
      val generatedSource = classloader.loadClass("play.templates.GeneratedSource")

      (generatedDir ** "*.template.scala").get.foreach {
        source =>
          val constructor = generatedSource.getDeclaredConstructor(classOf[java.io.File])
          val sync = generatedSource.getDeclaredMethod("sync")
          val generated = constructor.newInstance(source)
          try {
            sync.invoke(generated)
          } catch {
            case e: java.lang.reflect.InvocationTargetException => {
              val t = e.getTargetException
              t.printStackTrace()
              throw t
            }
          }
      }

      (sourceDirectory ** "*.scala.html").get.foreach {
        template =>
          val compile = compiler.getDeclaredMethod("compile", classOf[java.io.File], classOf[java.io.File], classOf[java.io.File], classOf[String], classOf[String])
          try {
            compile.invoke(null, template, sourceDirectory, generatedDir, "play.api.templates.HtmlFormat", "import play.api.templates._\nimport play.api.templates.PlayMagic._")
          } catch {
            case e: java.lang.reflect.InvocationTargetException => {
              streams.log.error("Compilation failed for %s".format(template))
              throw e.getTargetException
            }
          }
      }

      (generatedDir ** "*.scala").get.map(_.getAbsoluteFile)
  }

  def scalaTemplateSourceMappings = (excludeFilter in unmanagedSources, unmanagedSourceDirectories in Compile, baseDirectory) map {
    (excludes, sdirs, base) =>
      val scalaTemplateSources = sdirs.descendantsExcept("*.scala.html", excludes)
      ((scalaTemplateSources --- sdirs --- base) pair (relativeTo(sdirs) | relativeTo(base) | flat)) toSeq
  }

}