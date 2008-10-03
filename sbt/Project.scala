package sbt

import java.io.File
import java.util.jar.{Attributes, Manifest}
import FileUtilities._
import Project._

trait Project extends Logger
{
	val startTime = System.currentTimeMillis
	val jar = containingJar
	
	def info: ProjectInfo
	def analysis: ProjectAnalysis
	def actions: Map[String, Action]
	
	def act(name: String): Option[String] =
	{
		actions.get(name) match
		{
			case None => Some("Action '" + name + "' does not exist.")
			case Some(action) => action.run
		}
	}
	def getClasses(sources: PathFinder): PathFinder =
		Path.lazyPathFinder
		{
			val basePath = (compilePath ##)
			for(c <- analysis.getClasses(sources.get)) yield
				Path.relativize(basePath, c) match
				{
					case Some(relativized) => relativized
					case None => c
				}
		}
	
	trait Action extends NotNull
	{ a =>
	
		def run: Option[String]
		
		def &&(next: => Action) =
			new Action
			{
				def run = a.run orElse next.run
			}
		def ||(next: => Action) =
			new Action
			{
				def run = a.run flatMap(errorMessage => next.run)
			}
	}
	def isModified(source: Path) =
	{
		assert(source.asFile.exists, "Non-existing file " + source)
		analysis.getClasses(source) match
		{
			case None =>
			{
				debug("New file " + source)
				true
			}
			case Some(classes) =>
			{
				val sourceModificationTime = source.asFile.lastModified
				def isUpdated(p: Path) =
				{
					val f = p.asFile
					!f.exists || f.lastModified < sourceModificationTime
				}
				val modified = classes.find(isUpdated)
				if(modified.isDefined)
					debug("Modified class: " + modified.get)
				modified.isDefined
			}
		}
	}
	trait ConditionalAction extends Action
	{
		def sources: PathFinder
		def name: String
		def run(changedSources: Iterable[Path]): Option[String]
		
		def run: Option[String] =
		{
			import scala.collection.mutable.HashSet
			val sourcesSnapshot = sources.get
			val dirtySources: Iterable[Path] =
			{
				val removedSources = new HashSet[Path]
				removedSources ++= analysis.allSources
				removedSources --= sourcesSnapshot
				val removedCount = removedSources.size
				for(removed <- removedSources)
					analysis.removeDependent(removed)
				
				val unmodified = new HashSet[Path]
				val modified = new HashSet[Path]
				
				for(source <- sourcesSnapshot)
				{
					if(isModified(source))
					{
						debug("Source " + source + " directly modified.")
						modified += source
					}
					else
					{
						debug("Source " + source + " unmodified.")
						unmodified += source
					}
				}
				val directlyModifiedCount = modified.size
				
				def markDependenciesModified(changed: Iterable[Path]): List[Path] =
				{
					var newChanges: List[Path] = Nil
					for(changedPath <- changed; dependencies <- analysis.removeDependencies(changedPath);
						dependsOnChanged <- dependencies)
					{
						unmodified -= dependsOnChanged
						modified += dependsOnChanged
						newChanges = dependsOnChanged :: newChanges
					}
					newChanges
				}
				def propagateChanges(changed: Iterable[Path])
				{
					val newChanges = markDependenciesModified(changed)
					if(!newChanges.isEmpty)
						propagateChanges(newChanges)
				}
				
				propagateChanges(modified ++ markDependenciesModified(removedSources))
				for(changed <- removedSources ++ modified)
					analysis.removeSource(changed, Project.this)
				
				info("  Source analysis: " + directlyModifiedCount + " new/modifed, " +
					(modified.size-directlyModifiedCount) + " indirectly invalidated, " +
					removedCount + " removed.")
				
				modified
			}
			
			val result = run(dirtySources)
			info("  Post-analysis: " + analysis.allClasses.toSeq.length + " classes.")
			if(result.isEmpty)
				analysis.save(info, Project.this)
			else
				analysis.load(info, Project.this)
			result
		}
	}
	case class ErrorAction(message: String) extends Action
	{
		def run = Some(message)
	}
	
	trait ActionOption extends NotNull
	
	case class CompileOption(val asString: String) extends ActionOption
	trait PackageOption extends ActionOption
	trait TestOption extends ActionOption
	trait CleanOption extends ActionOption
	object IncrementVersion extends ActionOption
	object ClearAnalysis extends CleanOption
	
	case class ExcludeTests(classNames: Iterable[String]) extends TestOption
	
	case class ManifestOption(m: Manifest) extends PackageOption
	{
		assert(m != null)
	}
	case class MainClassOption(mainClassName: String) extends PackageOption
	case class JarName(name: String) extends PackageOption
	{
		Path.checkComponent(name)
	}
	case class OutputDirectory(path: Path) extends PackageOption
	
	val Deprecation = CompileOption("-deprecation")
	val ExplainTypes = CompileOption("-explaintypes")
	val Optimize = CompileOption("-optimise")
	val Verbose = CompileOption("-verbose")
	val Unchecked = CompileOption("-unchecked")
	val DisableWarnings = CompileOption("-nowarn")
	def target(target: Target.Value) = CompileOption("-target:" + target)
	object Target extends Enumeration
	{
		val Java1_5 = Value("jvm-1.5")
		val Java1_4 = Value("jvm-1.4")
		val Msil = Value("msil")
	}
	
	trait ScaladocOption extends ActionOption
	{
		def asList: List[String]
	}
	case class SimpleDocOption(optionValue: String) extends ScaladocOption
	{
		def asList = List(optionValue)
	}
	case class CompoundDocOption(label: String, value: String) extends ScaladocOption
	{
		def asList = List(label, value)
	}
	val LinkSource = SimpleDocOption("-linksource")
	val NoComment = SimpleDocOption("-nocomment")
	def access(access: Access.Value) = SimpleDocOption("-access:" + access)
	def documentBottom(bottomText: String) = CompoundDocOption("-bottom", bottomText)
	def documentCharset(charset: String) = CompoundDocOption("-charset", charset)
	def documentTitle(title: String) = CompoundDocOption("-doctitle", title)
	def documentFooter(footerText: String) = CompoundDocOption("-footer", footerText)
	def documentHeader(headerText: String) = CompoundDocOption("-header", headerText)
	def stylesheetFile(path: Path) = CompoundDocOption("-stylesheetfile", path.asFile.getAbsolutePath)
	def documentTop(topText: String) = CompoundDocOption("-top", topText)
	def windowTitle(title: String) = CompoundDocOption("-windowtitle", title)
	
	object Access extends Enumeration
	{
		val Public = Value("public")
		val Default = Value("protected")
		val Private = Value("private")
	}
	
	case class ConsoleAction(classpath: PathFinder) extends Action
	{
		def run: Option[String] = Run.console(classpath.get, Project.this)
	}
	case class RunAction(mainClass: Option[String], classpath: PathFinder, options: Seq[String]) extends Action
	{
		def run: Option[String] = Run(mainClass, classpath.get, options, Project.this)
	}
	case class CleanAction(paths: PathFinder, options: Iterable[CleanOption]) extends Action
	{
		def run: Option[String] =
		{
			val pathClean = FileUtilities.clean(paths.get, Project.this)
			if(options.elements.contains(ClearAnalysis))
			{
				analysis.clear
				analysis.save(info, Project.this)
			}
			pathClean
		}
	}
	case class TestAction(classpath: PathFinder, options: Iterable[TestOption]) extends Action
	{
		def run: Option[String] =
		{
			import scala.collection.mutable.HashSet
			
			val tests = HashSet.empty[String] ++ analysis.allTests
			for(ExcludeTests(exclude) <- options)
				tests -- exclude
				
			if(tests.isEmpty)
			{
				info("No tests to run.")
				None
			}
			else
				ScalaCheckTests(classpath.get, tests, Project.this)
		}
	}
	case class CompileAction(name: String,
		sources: PathFinder, outputDirectory: Path,
		options: Iterable[CompileOption]) extends ConditionalAction
	{
		def run(dirtySources: Iterable[Path]): Option[String] =
		{
			val dependencies = dependencyPath ** "*.jar"
			val classpathString = Path.makeString((outputDirectory +++ dependencies).get)
			val r = Compile(dirtySources, classpathString, outputDirectory, options.map(_.asString), Project.this)
			if(atLevel(Level.Debug))
			{
				val classes = scala.collection.mutable.HashSet(analysis.allClasses.toSeq: _*)
				var missed = 0
				for(c <- (outputDirectory ** "*.class").get)
				{
					if(!classes.contains(c))
					{
						missed += 1
						debug("Missed class: " + c)
					}
				}
				debug("Total missed classes: " + missed)
			}
			r
		}
	}
	
	case class ScaladocAction(name: String, sources: PathFinder, outputDirectory: Path,
		compiledPaths: PathFinder, options: Iterable[ScaladocOption]) extends Action
	{
		def run: Option[String] =
		{
			val dependencies = dependencyPath ** "*.jar"
			val classpathString = Path.makeString((compiledPaths +++ dependencies).get)
			Scaladoc(sources.get, classpathString, outputDirectory, options.flatMap(_.asList), Project.this)
		}
	}
	case class PackageAction(sources: PathFinder, options: Iterable[PackageOption]) extends Action
	{
		import scala.collection.jcl.Map
		/** Copies the mappings in a2 to a1, mutating a1. */
		private def mergeAttributes(a1: Attributes, a2: Attributes)
		{
			for( (key, value) <- Map(a2))
				a1.put(key, value)
		}
		def run: Option[String] =
		{
			import scala.collection.mutable.ListBuffer
			val manifest = new Manifest
			var jarName: Option[String] = None
			var outputDirectory: Option[Path] = None
			for(option <- options)
			{
				option match
				{
					case ManifestOption(mergeManifest) => 
					{
						mergeAttributes(manifest.getMainAttributes, mergeManifest.getMainAttributes)
						val entryMap = Map(manifest.getEntries)
						for((key, value) <- Map(mergeManifest.getEntries))
						{
							entryMap.get(key) match
							{
								case Some(attributes) => mergeAttributes(attributes, value)
								case None => entryMap.put(key, value)
							}
						}
					}
					case MainClassOption(mainClassName) => manifest.getMainAttributes.putValue(MainClassKey, mainClassName)
					case JarName(name) =>
						if(jarName.isEmpty)
							jarName = Some(name)
						else
							warn("Ignored duplicate jar name option " + name)
					case OutputDirectory(path) =>
						if(outputDirectory.isEmpty)
							outputDirectory = Some(path)
						else
							warn("Ignored duplicate output directory option " + path.toString)
					case _ => warn("Ignored unknown package option " + option)
				}
			}
			
			val jarPath = outputDirectory.getOrElse(path(DefaultOutputDirectoryName)) / jarName.getOrElse(defaultJarName)
			FileUtilities.pack(sources.get, jarPath, manifest, Project.this)
		}
	}
	
	def testClassName = "org.scalacheck.Properties"
	
	def defaultJarBaseName = info.name + "-" + info.currentVersion.toString
	def defaultJarName = defaultJarBaseName + ".jar"
	
	def outputDirectoryName = DefaultOutputDirectoryName
	def sourceDirectoryName = DefaultSourceDirectoryName
	def mainDirectoryName = DefaultMainDirectoryName
	def scalaDirectoryName = DefaultScalaDirectoryName
	def resourcesDirectoryName = DefaultResourcesDirectoryName
	def testDirectoryName = DefaultTestDirectoryName
	def compileDirectoryName = DefaultCompileDirectoryName
	def dependencyDirectoryName = DefaultDependencyDirectoryName
	def docDirectoryName = DefaultDocDirectoryName
	def apiDirectoryName = DefaultAPIDirectoryName
	
	def dependencyPath = path(dependencyDirectoryName)
	def outputPath = path(outputDirectoryName)
	def sourcePath = path(sourceDirectoryName)
	
	def mainSourcePath = sourcePath / mainDirectoryName
	def mainScalaSourcePath = mainSourcePath / scalaDirectoryName
	def mainResourcesPath = mainSourcePath / resourcesDirectoryName
	def mainDocPath = docPath / mainDirectoryName / apiDirectoryName
	
	def testSourcePath = sourcePath / testDirectoryName
	def testScalaSourcePath = testSourcePath / scalaDirectoryName
	def testResourcesPath = testSourcePath / resourcesDirectoryName
	def testDocPath = docPath / testDirectoryName / apiDirectoryName
	
	def compilePath = outputPath / compileDirectoryName
	def docPath = outputPath / docDirectoryName
	
	implicit def path(component: String): Path = info.projectPath / component
}

object Project
{
	private val log = new ConsoleLogger {}
	log.setLevel(Level.Trace)
	
	val DefaultSourceDirectoryName = "src"
	val DefaultOutputDirectoryName = "target"
	val DefaultCompileDirectoryName = "classes"
	val DefaultDocDirectoryName = "doc"
	val DefaultAPIDirectoryName = "api"
	
	val DefaultMainDirectoryName = "main"
	val DefaultScalaDirectoryName = "scala"
	val DefaultResourcesDirectoryName = "resources"
	val DefaultTestDirectoryName = "test"
	val DefaultDependencyDirectoryName = "lib"
	
	val MainClassKey = "Main-Class"
	
	import scala.collection.mutable.{HashMap, Map}
	private val projectMap: Map[Int, Project] = new HashMap
	private var nextID = 0
	private def getNextID =
	{
		val id = nextID
		nextID += 1
		id
	}
	def project(forID: Int) = projectMap(forID)
	
	def loadProject: Either[String, Project] = loadProject(new File("."))
	def loadProject(projectDirectory: File) =
	{
		try
		{
			for(info <- ProjectInfo.load(projectDirectory, getNextID, log).right;
				analysis <- ProjectAnalysis.load(info, log).right) yield
			{
				val builderClass = Class.forName(info.builderClassName)
				require(classOf[Project].isAssignableFrom(builderClass), "Builder class '" + builderClass + "' does not extend Project.")
				val constructor = builderClass.getDeclaredConstructor(classOf[ProjectInfo], classOf[ProjectAnalysis])
				val project = constructor.newInstance(info, analysis).asInstanceOf[Project]
				projectMap(info.id) = project
				project
			}
		}
		catch
		{
			case e: Exception =>
			{
				log.trace(e)
				Left("Error loading project: " + e.getMessage)
			}
		}
	}
}