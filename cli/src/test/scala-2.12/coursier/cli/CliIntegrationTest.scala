package coursier.cli

import java.io._
import java.util.zip.ZipInputStream

import argonaut.Argonaut._
import coursier.cli.util.{DepNode, ReportNode}
import caseapp.core.RemainingArgs
import coursier.cli.options._
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class CliIntegrationTest extends FlatSpec {

  def withFile(content: String = "")(testCode: (File, FileWriter) => Any) {
    val file = File.createTempFile("hello", "world") // create the fixture
    val writer = new FileWriter(file)
    writer.write(content)
    writer.flush()
    try {
      testCode(file, writer) // "loan" the fixture to the test
    }
    finally {
      writer.close()
      file.delete()
    }
  }

  def getReportFromJson(f: File): ReportNode = {
    // Parse back the output json file
    val source = scala.io.Source.fromFile(f)
    val str = try source.mkString finally source.close()

    str.decodeEither[ReportNode] match {
      case Left(error) =>
        throw new Exception(s"Error while decoding report: $error")
      case Right(report) => report
    }
  }

  "Normal fetch" should "get all files" in {

    val fetchOpt = FetchOptions(common = CommonOptions())
    val fetch = Fetch(fetchOpt, RemainingArgs(Seq("junit:junit:4.12"), Seq()))
    assert(fetch.files0.map(_.getName).toSet.equals(Set("junit-4.12.jar", "hamcrest-core-1.3.jar")))

  }

  "Module level" should "exclude correctly" in withFile(
    "junit:junit--org.hamcrest:hamcrest-core") { (file, _) =>
    withFile() { (jsonFile, _) =>
      val commonOpt = CommonOptions(localExcludeFile = file.getAbsolutePath, jsonOutputFile = jsonFile.getPath)
      val fetchOpt = FetchOptions(common = commonOpt)

      val fetch = Fetch(fetchOpt, RemainingArgs(Seq("junit:junit:4.12"), Seq()))
      val filesFetched = fetch.files0.map(_.getName).toSet
      val expected = Set("junit-4.12.jar")
      assert(filesFetched.equals(expected), s"files fetched: $filesFetched not matching expected: $expected")

      val node: ReportNode = getReportFromJson(jsonFile)

      assert(node.dependencies.length == 1)
      assert(node.dependencies.head.coord == "junit:junit:4.12")
    }

  }

  /**
    * Result without exclusion:
    * |└─ org.apache.avro:avro:1.7.4
    * |├─ com.thoughtworks.paranamer:paranamer:2.3
    * |├─ org.apache.commons:commons-compress:1.4.1
    * |│  └─ org.tukaani:xz:1.0 // this should be fetched
    * |├─ org.codehaus.jackson:jackson-core-asl:1.8.8
    * |├─ org.codehaus.jackson:jackson-mapper-asl:1.8.8
    * |│  └─ org.codehaus.jackson:jackson-core-asl:1.8.8
    * |├─ org.slf4j:slf4j-api:1.6.4
    * |└─ org.xerial.snappy:snappy-java:1.0.4.1
    */
  "avro exclude xz" should "not fetch xz" in withFile(
    "org.apache.avro:avro--org.tukaani:xz") { (file, writer) =>
    withFile() { (jsonFile, _) =>
      val commonOpt = CommonOptions(localExcludeFile = file.getAbsolutePath, jsonOutputFile = jsonFile.getPath)
      val fetchOpt = FetchOptions(common = commonOpt)

      val fetch = Fetch(fetchOpt, RemainingArgs(Seq("org.apache.avro:avro:1.7.4"), Seq()))

      val filesFetched = fetch.files0.map(_.getName).toSet
      assert(!filesFetched.contains("xz-1.0.jar"))

      val node: ReportNode = getReportFromJson(jsonFile)

      // assert root level dependencies
      assert(node.dependencies.map(_.coord).toSet == Set(
        "org.apache.avro:avro:1.7.4",
        "com.thoughtworks.paranamer:paranamer:2.3",
        "org.apache.commons:commons-compress:1.4.1",
        "org.codehaus.jackson:jackson-core-asl:1.8.8",
        "org.codehaus.jackson:jackson-mapper-asl:1.8.8",
        "org.slf4j:slf4j-api:1.6.4",
        "org.xerial.snappy:snappy-java:1.0.4.1"
      ))

      // org.apache.commons:commons-compress:1.4.1 should not contain deps underneath it.
      val compressNode = node.dependencies.find(_.coord == "org.apache.commons:commons-compress:1.4.1")
      assert(compressNode.isDefined)
      assert(compressNode.get.dependencies.isEmpty)
    }
  }

  /**
    * Result without exclusion:
    * |├─ org.apache.avro:avro:1.7.4
    * |│  ├─ com.thoughtworks.paranamer:paranamer:2.3
    * |│  ├─ org.apache.commons:commons-compress:1.4.1
    * |│  │  └─ org.tukaani:xz:1.0
    * |│  ├─ org.codehaus.jackson:jackson-core-asl:1.8.8
    * |│  ├─ org.codehaus.jackson:jackson-mapper-asl:1.8.8
    * |│  │  └─ org.codehaus.jackson:jackson-core-asl:1.8.8
    * |│  ├─ org.slf4j:slf4j-api:1.6.4
    * |│  └─ org.xerial.snappy:snappy-java:1.0.4.1
    * |└─ org.apache.commons:commons-compress:1.4.1
    * |   └─ org.tukaani:xz:1.0
    */
  "avro excluding xz + commons-compress" should "still fetch xz" in withFile(
    "org.apache.avro:avro--org.tukaani:xz") {
    (file, writer) =>

      withFile() {
        (jsonFile, _) => {
          val commonOpt = CommonOptions(localExcludeFile = file.getAbsolutePath, jsonOutputFile = jsonFile.getPath)
          val fetchOpt = FetchOptions(common = commonOpt)

          val fetch = Fetch(fetchOpt, RemainingArgs(Seq("org.apache.avro:avro:1.7.4", "org.apache.commons:commons-compress:1.4.1"), Seq()))
          val filesFetched = fetch.files0.map(_.getName).toSet
          assert(filesFetched.contains("xz-1.0.jar"))

          val node: ReportNode = getReportFromJson(jsonFile)

          // Root level org.apache.commons:commons-compress:1.4.1 should have org.tukaani:xz:1.0 underneath it.
          val compressNode = node.dependencies.find(_.coord == "org.apache.commons:commons-compress:1.4.1")
          assert(compressNode.isDefined)
          assert(compressNode.get.dependencies.contains("org.tukaani:xz:1.0"))

          val innerCompressNode = node.dependencies.find(_.coord == "org.apache.avro:avro:1.7.4")
          assert(innerCompressNode.isDefined)
          assert(!innerCompressNode.get.dependencies.contains("org.tukaani:xz:1.0"))
        }
      }

  }

  /**
    * Result:
    * |├─ org.apache.commons:commons-compress:1.4.1
    * |│  └─ org.tukaani:xz:1.0 -> 1.1
    * |└─ org.tukaani:xz:1.1
    */
  "requested xz:1.1" should "not have conflicts" in withFile() {
    (excludeFile, writer) =>
      withFile() {
        (jsonFile, _) => {
          val commonOpt = CommonOptions(jsonOutputFile = jsonFile.getPath)
          val fetchOpt = FetchOptions(common = commonOpt)

          Fetch.run(fetchOpt, RemainingArgs(Seq("org.apache.commons:commons-compress:1.4.1", "org.tukaani:xz:1.1"), Seq()))

          val node: ReportNode = getReportFromJson(jsonFile)
          assert(node.conflict_resolution.isEmpty)
        }
      }
  }

  /**
    * Result:
    * |├─ org.apache.commons:commons-compress:1.5
    * |│  └─ org.tukaani:xz:1.2
    * |└─ org.tukaani:xz:1.1 -> 1.2
    */
  "org.apache.commons:commons-compress:1.5 org.tukaani:xz:1.1" should "have conflicts" in withFile() {
    (excludeFile, _) =>
      withFile() {
        (jsonFile, _) => {
          val commonOpt = CommonOptions(jsonOutputFile = jsonFile.getPath)
          val fetchOpt = FetchOptions(common = commonOpt)

          Fetch.run(fetchOpt, RemainingArgs(Seq("org.apache.commons:commons-compress:1.5", "org.tukaani:xz:1.1"), Seq()))

          val node: ReportNode = getReportFromJson(jsonFile)
          assert(node.conflict_resolution == Map("org.tukaani:xz:1.1" -> "org.tukaani:xz:1.2"))
        }
      }
  }

  /**
    * Result:
    * |└─ org.apache.commons:commons-compress:1.5
    * |   └─ org.tukaani:xz:1.2
    */
  "classifier tests" should "have tests.jar" in withFile() {
    (excludeFile, _) =>
      withFile() {
        (jsonFile, _) => {
          val commonOpt = CommonOptions(jsonOutputFile = jsonFile.getPath)
          val fetchOpt = FetchOptions(common = commonOpt)

          Fetch.run(
            fetchOpt,
            RemainingArgs(Seq("org.apache.commons:commons-compress:1.5,classifier=tests"), Seq())
          )

          val node: ReportNode = getReportFromJson(jsonFile)

          val compressNode = node.dependencies.find(_.coord == "org.apache.commons:commons-compress:1.5")
          assert(compressNode.isDefined)
          assert(compressNode.get.files.head._1 == "tests")
          assert(compressNode.get.files.head._2.contains("commons-compress-1.5-tests.jar"))
          assert(compressNode.get.dependencies.contains("org.tukaani:xz:1.2"))
        }
      }
  }

  /**
    * Result:
    * |├─ org.apache.commons:commons-compress:1.5
    * |│  └─ org.tukaani:xz:1.2
    * |└─ org.apache.commons:commons-compress:1.5
    * |   └─ org.tukaani:xz:1.2
    */
  "mixed vanilla and classifier " should "have tests.jar and .jar" in withFile() {
    (excludeFile, _) =>
      withFile() {
        (jsonFile, _) => {
          val commonOpt = CommonOptions(jsonOutputFile = jsonFile.getPath)
          val fetchOpt = FetchOptions(common = commonOpt)

          Fetch.run(
            fetchOpt,
            RemainingArgs(
              Seq(
                "org.apache.commons:commons-compress:1.5,classifier=tests",
                "org.apache.commons:commons-compress:1.5"
              ),
              Seq()
            )
          )

          val node: ReportNode = getReportFromJson(jsonFile)

          val compressNodes: Seq[DepNode] = node.dependencies
            .filter(_.coord == "org.apache.commons:commons-compress:1.5")
            .sortBy(_.files.head._1.length) // sort by first classifier length
          assert(compressNodes.length == 2)
          assert(compressNodes.head.files.head._1 == "")
          assert(compressNodes.head.files.head._2.contains("commons-compress-1.5.jar"))

          assert(compressNodes.last.files.head._1 == "tests")
          assert(compressNodes.last.files.head._2.contains("commons-compress-1.5-tests.jar"))
        }
      }
  }

  /**
    * Result:
    * |└─ org.apache.commons:commons-compress:1.5
    * |   └─ org.tukaani:xz:1.2 // should not be fetched
    */
  "intransitive" should "only fetch a single jar" in withFile() {
    (_, _) =>
      withFile() {
        (jsonFile, _) => {
          val commonOpt = CommonOptions(jsonOutputFile = jsonFile.getPath, intransitive = List("org.apache.commons:commons-compress:1.5"))
          val fetchOpt = FetchOptions(common = commonOpt)

          Fetch.run(fetchOpt, RemainingArgs(Nil, Nil))

          val node: ReportNode = getReportFromJson(jsonFile)
          val compressNode = node.dependencies.find(_.coord == "org.apache.commons:commons-compress:1.5")
          assert(compressNode.isDefined)
          assert(compressNode.get.files.head._1 == "")
          assert(compressNode.get.files.head._2.contains("commons-compress-1.5.jar"))

          assert(compressNode.get.dependencies.isEmpty)
        }
      }
  }

  /**
    * Result:
    * |└─ org.apache.commons:commons-compress:1.5
    * |   └─ org.tukaani:xz:1.2
    */
  "intransitive classifier" should "only fetch a single tests jar" in withFile() {
    (excludeFile, _) =>
      withFile() {
        (jsonFile, _) => {
          val commonOpt = CommonOptions(jsonOutputFile = jsonFile.getPath, intransitive = List("org.apache.commons:commons-compress:1.5,classifier=tests"))
          val fetchOpt = FetchOptions(common = commonOpt)

          Fetch.run(fetchOpt, RemainingArgs(Seq(), Seq()))

          val node: ReportNode = getReportFromJson(jsonFile)

          val compressNode = node.dependencies.find(_.coord == "org.apache.commons:commons-compress:1.5")
          assert(compressNode.isDefined)
          assert(compressNode.get.files.head._1 == "tests")
          assert(compressNode.get.files.head._2.contains("commons-compress-1.5-tests.jar"))

          assert(compressNode.get.dependencies.isEmpty)
        }
      }
  }

  /**
    * Result:
    * |└─ org.apache.commons:commons-compress:1.5 -> 1.4.1
    * |   └─ org.tukaani:xz:1.0
    */
  "classifier with forced version" should "fetch tests jar" in withFile() {
    (excludeFile, _) =>
      withFile() {
        (jsonFile, _) => {
          val commonOpt = CommonOptions(jsonOutputFile = jsonFile.getPath, forceVersion = List("org.apache.commons:commons-compress:1.4.1"))
          val fetchOpt = FetchOptions(common = commonOpt)

          Fetch(
            fetchOpt,
            RemainingArgs(Seq("org.apache.commons:commons-compress:1.5,classifier=tests"), Seq())
          )

          val node: ReportNode = getReportFromJson(jsonFile)

          assert(!node.dependencies.exists(_.coord == "org.apache.commons:commons-compress:1.5"))

          val compressNode = node.dependencies.find(_.coord == "org.apache.commons:commons-compress:1.4.1")
          assert(compressNode.isDefined)
          assert(compressNode.get.files.head._1 == "tests")
          assert(compressNode.get.files.head._2.contains("commons-compress-1.4.1-tests.jar"))

          assert(compressNode.get.dependencies.size == 1)
          assert(compressNode.get.dependencies.head == "org.tukaani:xz:1.0")
        }
      }
  }

  /**
    * Result:
    * |└─ org.apache.commons:commons-compress:1.5 -> 1.4.1
    * |   └─ org.tukaani:xz:1.0 // should not be there
    */
  "intransitive, classifier, forced version" should "fetch a single tests jar" in withFile() {
    (excludeFile, _) =>
      withFile() {
        (jsonFile, _) => {
          val commonOpt = CommonOptions(jsonOutputFile = jsonFile.getPath,
            intransitive = List("org.apache.commons:commons-compress:1.5,classifier=tests"),
            forceVersion = List("org.apache.commons:commons-compress:1.4.1"))
          val fetchOpt = FetchOptions(common = commonOpt)

          Fetch.run(fetchOpt, RemainingArgs(Seq(), Seq()))

          val node: ReportNode = getReportFromJson(jsonFile)

          assert(!node.dependencies.exists(_.coord == "org.apache.commons:commons-compress:1.5"))

          val compressNode = node.dependencies.find(_.coord == "org.apache.commons:commons-compress:1.4.1")
          assert(compressNode.isDefined)
          assert(compressNode.get.files.head._1 == "tests")
          assert(compressNode.get.files.head._2.contains("commons-compress-1.4.1-tests.jar"))

          assert(compressNode.get.dependencies.isEmpty)
        }
      }
  }

  "profiles" should "be manually (de)activated" in withFile() {
    (jsonFile, _) =>
      val commonOpt = CommonOptions(
        jsonOutputFile = jsonFile.getPath,
        profile = List("scala-2.10", "!scala-2.11")
      )
      val fetchOpt = FetchOptions(common = commonOpt)

      Fetch(
        fetchOpt,
        RemainingArgs(Seq("org.apache.spark:spark-core_2.10:2.2.1"), Seq())
      )

      val node = getReportFromJson(jsonFile)

      assert(node.dependencies.exists(_.coord.startsWith("org.scala-lang:scala-library:2.10.")))
      assert(!node.dependencies.exists(_.coord.startsWith("org.scala-lang:scala-library:2.11.")))
  }

  "bootstrap" should "not add POMs to the classpath" in withFile() {

    def zipEntryContent(zis: ZipInputStream, path: String): Array[Byte] = {
      val e = zis.getNextEntry
      if (e == null)
        throw new NoSuchElementException(s"Entry $path in zip file")
      else if (e.getName == path)
        coursier.Platform.readFullySync(zis)
      else
        zipEntryContent(zis, path)
    }

    (bootstrapFile, _) =>
      val artifactOptions = ArtifactOptions()
      val common = CommonOptions(
        repository = List("bintray:scalameta/maven")
      )
      val isolatedLoaderOptions = IsolatedLoaderOptions(
        isolateTarget = List("foo"),
        isolated = List("foo:org.scalameta:trees_2.12:1.7.0")
      )
      val bootstrapSpecificOptions = BootstrapSpecificOptions(
        output = bootstrapFile.getPath,
        isolated = isolatedLoaderOptions,
        force = true,
        common = common
      )
      val bootstrapOptions = BootstrapOptions(artifactOptions, bootstrapSpecificOptions)

      Bootstrap.run(
        bootstrapOptions,
        RemainingArgs(Seq("com.geirsson:scalafmt-cli_2.12:1.4.0"), Seq())
      )

      var fis: InputStream = null

      val content = try {
        fis = new FileInputStream(bootstrapFile)
        coursier.Platform.readFullySync(fis)
      } finally {
        if (fis != null) fis.close()
      }

      val actualContent = {
        val header = Seq[Byte](0x50, 0x4b, 0x03, 0x04)
        val idx = content.indexOfSlice(header)
        if (idx < 0)
          throw new Exception(s"ZIP header not found in ${bootstrapFile.getPath}")
        else
          content.drop(idx)
      }

      val zis = new ZipInputStream(new ByteArrayInputStream(actualContent))

      val lines = new String(zipEntryContent(zis, "bootstrap-isolation-foo-jar-urls"), "UTF-8").lines.toVector

      val extensions = lines
        .map { l =>
          val idx = l.lastIndexOf('.')
          if (idx < 0)
            l
          else
            l.drop(idx + 1)
        }
        .toSet

      assert(extensions == Set("jar"))
  }
}
