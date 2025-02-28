package caliban.codegen

import _root_.caliban.tools.Codegen.GenType
import _root_.caliban.tools._
import sbt._

import java.io.File
import java.net.URL

object CalibanSourceGenerator {
  import zio._
  import zio.console._

  import sjsonnew.{ :*:, LList, LNil }

  case class TrackedSettings(arguments: Seq[String])
  object TrackedSettings {
    import _root_.sbt.util.CacheImplicits._

    def fromSettings(
      sources: Seq[File],
      fileSettings: Seq[CalibanFileSettings],
      urlSettings: Seq[CalibanUrlSettings]
    ): TrackedSettings = {
      val allSettings: Seq[CalibanSettings] = sources.toList.map(collectSettingsFor(fileSettings, _)) ++ urlSettings
      TrackedSettings(allSettings.map(_.toString))
    }

    implicit val analysisIso = LList.iso[TrackedSettings, Seq[String] :*: LNil](
      { case TrackedSettings(arguments) => ("args", arguments) :*: LNil },
      { case ((_, args) :*: LNil) => TrackedSettings(args) }
    )
  }

  def transformFile(sourceRoot: File, managedRoot: File, settings: CalibanSettings): File => File = { graphqlFile =>
    val relativePath = settings.packageName.fold(sourceRoot.toPath.relativize(graphqlFile.toPath)) { pkg =>
      val components = pkg.split('.').toList.map(file(_).toPath) :+ graphqlFile.toPath.getFileName
      components.reduceLeft(_.resolve(_))
    }
    val interimPath  = managedRoot.toPath.resolve(relativePath)
    val clientName   = settings.clientName.getOrElse(interimPath.getFileName.toString.stripSuffix(".graphql"))
    val scalaName    = clientName + ".scala"
    interimPath.getParent.resolve(scalaName).toFile
  }

  def collectSettingsFor(fileSettings: Seq[CalibanFileSettings], source: File): CalibanFileSettings =
    // Supply a default packageName.
    // If we do not, `src_managed.main.caliban-codegen-sbt` will be used,
    // which is not only terrible, but invalid.
    CalibanSettings
      .emptyFile(source)
      .packageName("caliban")
      .append(
        fileSettings
          .collect({ case needle if source.toPath.endsWith(needle.file.toPath) => needle })
          .foldLeft[CalibanFileSettings](CalibanSettings.emptyFile(source)) { case (acc, next) =>
            acc.append(next)
          }
      )

  def apply(
    sourceRoot: File,
    sources: Seq[File],
    sourceManaged: File,
    cacheDirectory: File,
    fileSettings: Seq[CalibanFileSettings],
    urlSettings: Seq[CalibanUrlSettings]
  ): List[File] = {
    import sbt.util.CacheImplicits._

    def generateSources: List[File] = {
      def generateFileSource(graphql: File, settings: CalibanSettings): IO[Option[Throwable], List[File]] = for {
        generatedSource <- ZIO.succeed(transformFile(sourceRoot, sourceManaged, settings)(graphql))
        _               <- Task(sbt.IO.createDirectory(generatedSource.toPath.getParent.toFile)).asSomeError
        opts            <- ZIO.fromOption(Some(settings.toOptions(graphql.toString, generatedSource.toString)))
        files           <- Codegen.generate(opts, GenType.Client).asSomeError
      } yield files

      def generateUrlSource(graphql: URL, settings: CalibanSettings): IO[Option[Throwable], List[File]] = for {
        generatedSource <-
          ZIO.succeed(
            transformFile(sourceRoot, sourceManaged, settings)(new java.io.File(graphql.getPath.stripPrefix("/")))
          )
        _               <- Task(sbt.IO.createDirectory(generatedSource.toPath.getParent.toFile)).asSomeError
        opts            <- ZIO.fromOption(Some(settings.toOptions(graphql.toString, generatedSource.toString)))
        files           <- Codegen.generate(opts, GenType.Client).asSomeError
      } yield files

      Runtime.default
        .unsafeRun(
          for {
            fromFiles <- ZIO.foreach(sources.toList)(source =>
                           generateFileSource(source, collectSettingsFor(fileSettings, source)).catchAll {
                             case Some(reason) =>
                               putStrLn(reason.toString) *> putStrLn(reason.getStackTrace.mkString("\n")).as(List.empty)
                             case None         => ZIO.succeed(List.empty)
                           }
                         )
            fromUrls  <- ZIO.foreach(urlSettings)(setting =>
                           generateUrlSource(setting.url, setting).catchAll {
                             case Some(reason) =>
                               putStrLn(reason.toString) *> putStrLn(reason.getStackTrace.mkString("\n")).as(List.empty)
                             case None         => ZIO.succeed(List.empty)
                           }
                         )
          } yield (fromFiles ++ fromUrls).flatten
        )
    }

    // NB: This is heavily inspired by the caching technique from eed3si9n's sbt-scalaxb plugin
    def cachedGenerateSources =
      Tracked.inputChanged(cacheDirectory / "caliban-inputs") {
        (inChanged, _: (List[File], FilesInfo[ModifiedFileInfo], String, TrackedSettings)) =>
          Tracked.outputChanged(cacheDirectory / "caliban-output") { (outChanged, outputs: FilesInfo[PlainFileInfo]) =>
            if (inChanged || outChanged) generateSources
            else outputs.files.toList.map(_.file)
          }
      }

    def inputs: (List[File], FilesInfo[ModifiedFileInfo], String, TrackedSettings) =
      (
        sources.toList,
        FilesInfo.lastModified(sources.toSet).asInstanceOf[FilesInfo[ModifiedFileInfo]],
        BuildInfo.version,
        TrackedSettings.fromSettings(sources, fileSettings, urlSettings)
      )

    cachedGenerateSources(inputs)(() =>
      FilesInfo.exists((sourceManaged ** "*.scala").get.toSet).asInstanceOf[FilesInfo[PlainFileInfo]]
    )
  }
}
