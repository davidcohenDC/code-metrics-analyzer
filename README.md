# Code Metrics Analyzer

A parallel file processing application built with Scala 3 and Akka Typed actors. The application walks through directory structures, analyzes Java source files to count lines of code, and provides real-time statistics through an interactive GUI.

## Quick Start

```bash
# Compile the project
sbt compile

# Run the application
sbt run

# Run tests
sbt test

# Create standalone JAR (optional)
sbt assembly
```

## Features

- **Parallel Processing**: Leverages Akka Typed actors for concurrent file processing
- **Interactive GUI**: Real-time visualization of processing results with Swing-based interface
- **Lines of Code Analysis**: Counts LOC in Java files and ranks them
- **Distribution Metrics**: Shows statistical distribution of file sizes across configurable intervals
- **Configurable Parallelism**: Adjust the number of concurrent processors via configuration
- **Actor-Based Architecture**: Clean separation of concerns using the actor model
  - `SourceActor`: File system crawler
  - `QueueActor`: Work distribution queue
  - `ProcessorActor`: Parallel file processors
  - `ReducerActor`: Result aggregation
  - `SupervisorActor`: System coordination
  - `UiBridgeActor`: GUI communication bridge

## Architecture

The application follows a pipeline architecture pattern:

```
Source (FS Crawler) → Queue → Processors (Parallel) → Reducer → UI Bridge → GUI
                                    ↓
                              SupervisorActor (Coordination)
```

Each component is implemented as an independent actor, enabling scalable parallel processing and clean separation of concerns.

## Requirements

- **Java**: JDK 11 or higher
- **Scala**: 3.4.2 (managed by sbt)
- **sbt**: 1.x (Scala Build Tool)

## Installation & Compilation

### 1. Clone the repository

```bash
cd third-assignment-ex1
```

### 2. Compile the project

Using sbt:

```bash
sbt compile
```

This will:
- Download all dependencies (Akka, Scala Swing, Logback, etc.)
- Compile the Scala source files
- Set up the project for running

### 3. Run tests (optional)

```bash
sbt test
```

### 4. Create a runnable assembly (optional)

To create a standalone JAR file:

```bash
sbt assembly
```

This creates a fat JAR in `target/scala-3/` that you can distribute and run anywhere with Java installed.

## Running the Application

### Using sbt (recommended for development)

```bash
sbt run
```

### Using the compiled JAR

If you created an assembly:

```bash
java -jar target/scala-3/code-metrics-analyzer-assembly-0.1.0-SNAPSHOT.jar
```

### Using the application

1. The GUI will launch automatically
2. Click **Browse** to select a directory containing Java files
3. Configure analysis parameters:
   - **Max Files**: Number of top files to display (by LOC)
   - **Num Intervals**: Number of bins for the distribution histogram
   - **Max Length**: Maximum LOC value for distribution analysis
4. Click **Start** to begin analysis
5. View results in real-time:
   - **Max Files panel**: Top N files ranked by lines of code
   - **Distribution panel**: Histogram showing file size distribution

## Configuration

Edit `src/main/resources/application.conf` to customize:

```hocon
source {
  system-name = "CrawlerSystem"
  processors  = 0              # 0 = auto (uses available processors)
  log-level   = "DEBUG"        # DEBUG, INFO, WARN, ERROR

  processor {
     timeout = 5s              # Timeout for processing individual files
     max-retries = 3           # Retry attempts for failed jobs
  }

  dispatcher {
    thread-pool-executor {
      fixed-pool-size = 16     # Thread pool size
    }
  }
}
```

### Parallelism

- Set `processors = 0` to automatically use `Runtime.getRuntime.availableProcessors()`
- Set `processors = N` to use exactly N processor actors


## Technologies

- **Scala 3.4.2**: Modern functional programming language
- **Akka Typed 2.8.8**: Actor-based concurrency framework
- **Scala Swing 3.0.0**: GUI framework
- **Scala Toolkit 0.7.0**: OS-Lib for file system operations
- **Logback 1.5.19**: Logging framework
- **ScalaTest 3.2.19**: Testing framework

## License

This project is part of a university assignment for the Concurrent and Distributed Programming course.

## Contributing

This is an academic project. If you find issues or have suggestions, feel free to open an issue or submit a pull request.
