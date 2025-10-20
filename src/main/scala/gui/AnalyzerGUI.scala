package gui

import actors.ReducerActor
import actors.SupervisorActor
import actors.UiBridgeActor
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors

import java.awt.*
import java.nio.file.Paths
import javax.swing.*
import scala.swing.Swing
import scala.util.Try

class AnalyzerGUI(
    supervisorRef: ActorRef[SupervisorActor.Command],
    uiBridgeRef: ActorRef[UiBridgeActor.Command],
    system: ActorSystem[?],
):

  // Main frame as class field to avoid null usage
  private val frame = new JFrame("Code Metrics Analyzer")

  private val directoryField = new JTextField()
  private val maxFilesField = new JTextField("10")
  private val numIntervalsField = new JTextField("10")
  private val maxLengthField = new JTextField("100")

  private val startButton = new JButton("Start")
  private val observeButton = new JButton("Subscribe")
  private val stopObserveButton = new JButton("Unsubscribe")

  private val maxFilesArea = new JTextArea()
  private val distributionArea = new JTextArea()
  private val statusLabel = new JLabel("IDLE")

  // === Local state helpers ===
  private def enableStart(): Unit = startButton.setEnabled(true)

  private def disableStart(): Unit = startButton.setEnabled(false)

  private def enableObserveButtons(): Unit =
    observeButton.setEnabled(false)
    stopObserveButton.setEnabled(true)

  private def disableObserveButtons(): Unit =
    observeButton.setEnabled(true)
    stopObserveButton.setEnabled(false)

  // === GUI subscriber actor ===
  private val guiSink =
    system.systemActorOf(
      Behaviors.receiveMessage[UiBridgeActor.Event] {
        case UiBridgeActor.Event.UiState(top, dist) =>
          Swing.onEDT {
            val limit = Try(maxFilesField.getText.trim.toInt).getOrElse(10)
            maxFilesArea.setText(top.take(limit).map(_.toString).mkString("\n"))
            distributionArea.setText(dist)
          }
          Behaviors.same

        case UiBridgeActor.Event.Status(lbl) =>
          Swing.onEDT {
            statusLabel.setText(lbl)
          }
          Behaviors.same

        case UiBridgeActor.Event.AllDone =>
          Swing.onEDT {
            statusLabel.setText("IDLE")
            enableStart()
          }
          Behaviors.same
      },
      "ui-sink",
    )

  def initGUI(): Unit =
    frame.addWindowListener(
      new java.awt.event.WindowAdapter:
        override def windowClosing(e: java.awt.event.WindowEvent): Unit =
          system.terminate()
          sys.exit(0),
    )

    frame.setPreferredSize(new Dimension(900, 620))

    val inputPanel = new JPanel(new GridBagLayout())
    inputPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10))
    val gbc = new GridBagConstraints()
    gbc.insets = Insets(5, 5, 5, 5)
    gbc.anchor = GridBagConstraints.LINE_START

    def addRow(label: String, field: JComponent, y: Int, extra: Option[JComponent] = None): Unit =
      gbc.gridx = 0;
      gbc.gridy = y
      inputPanel.add(new JLabel(label), gbc)
      gbc.gridx = 1
      inputPanel.add(field, gbc)
      extra.foreach { comp =>
        gbc.gridx = 2
        inputPanel.add(comp, gbc)
      }

    val browseButton = new JButton("Browse")
    browseButton.addActionListener(_ => chooseDirectory())
    directoryField.setPreferredSize(new Dimension(360, 25))
    maxFilesField.setPreferredSize(new Dimension(100, 25))
    numIntervalsField.setPreferredSize(new Dimension(100, 25))
    maxLengthField.setPreferredSize(new Dimension(100, 25))

    addRow("Directory:", directoryField, 0, Some(browseButton))
    addRow("Max Files:", maxFilesField, 1)
    addRow("Num Intervals:", numIntervalsField, 2)
    addRow("Max Length:", maxLengthField, 3)

    // Buttons panel
    startButton.addActionListener(_ => startAnalyzer())
    observeButton.addActionListener(_ => subscribe())
    stopObserveButton.addActionListener(_ => unsubscribe())

    val statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT))
    statusPanel.add(new JLabel("STATUS: "))
    statusPanel.add(statusLabel)

    val buttonPanelLeft = new JPanel(new FlowLayout(FlowLayout.LEFT))
    buttonPanelLeft.add(observeButton)
    buttonPanelLeft.add(stopObserveButton)

    val buttonPanelRight = new JPanel(new FlowLayout(FlowLayout.RIGHT))
    buttonPanelRight.add(startButton)

    val southPanel = new JPanel(new BorderLayout())
    southPanel.add(statusPanel, BorderLayout.WEST)
    southPanel.add(buttonPanelLeft, BorderLayout.CENTER)
    southPanel.add(buttonPanelRight, BorderLayout.EAST)

    val centerPanel = new JPanel(new GridLayout(1, 2))
    centerPanel.add(createTextAreaPanel("Max Files", maxFilesArea))
    centerPanel.add(createTextAreaPanel("Distribution", distributionArea))

    frame.add(inputPanel, BorderLayout.NORTH)
    frame.add(centerPanel, BorderLayout.CENTER)
    frame.add(southPanel, BorderLayout.SOUTH)

    frame.pack()
    val screen = Toolkit.getDefaultToolkit.getScreenSize
    val x = (screen.width - frame.getWidth) / 2
    val y = (screen.height - frame.getHeight) / 2
    frame.setLocation(x, y)
    frame.setVisible(true)

    subscribe()

  private def createTextAreaPanel(title: String, textArea: JTextArea): JPanel =
    val panel = new JPanel(new BorderLayout())
    panel.setBorder(BorderFactory.createTitledBorder(title))
    panel.add(new JScrollPane(textArea), BorderLayout.CENTER)
    panel

  private def chooseDirectory(): Unit =
    val chooser = new JFileChooser()
    chooser.setDialogTitle("Select a directory")
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY)
    if chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION then
      directoryField.setText(chooser.getSelectedFile.getAbsolutePath)

  private def subscribe(): Unit =
    uiBridgeRef ! UiBridgeActor.Command.Subscribe(guiSink.narrow)
    enableObserveButtons()

  private def unsubscribe(): Unit =
    uiBridgeRef ! UiBridgeActor.Command.Unsubscribe(guiSink.narrow)
    disableObserveButtons()

  private def startAnalyzer(): Unit =
    val dir = directoryField.getText.trim
    if dir.isEmpty then showErrorDialog("Please specify a directory.")
    else
      val validated = for
        maxLength <- parseField(maxLengthField, "max length")
        maxFiles <- parseField(maxFilesField, "max files")
        numIntv <- parseField(numIntervalsField, "num intervals")
        _ <- validateLimits(maxFiles, maxLength, "Max Files")
        _ <- validateLimits(numIntv, maxLength, "Num Intervals")
      yield (maxFiles, numIntv, maxLength)

      validated match
        case Some((maxFiles, numIntv, maxLength)) =>
          val params = ReducerActor.ReduceParams(maxFiles = maxFiles, bins = numIntv, maxLen = maxLength)
          supervisorRef ! SupervisorActor.Command.Run(os.Path(Paths.get(dir)), params)
          subscribe()
          disableStart()
        case None => ()

  private def showErrorDialog(message: String): Unit =
    Swing.onEDT {
      JOptionPane.showMessageDialog(frame, message, "Error", JOptionPane.ERROR_MESSAGE)
    }

  private def parseField(field: JTextField, name: String): Option[Int] =
    Try(field.getText.trim.toInt).toOption.orElse {
      showErrorDialog(s"Invalid value for $name: '${field.getText.trim}'")
      None
    }

  private def validateLimits(value: Int, limit: Int, name: String): Option[Unit] =
    if value <= limit then Some(())
    else
      showErrorDialog(s"$name value must be less than max length ($limit).")
      None
