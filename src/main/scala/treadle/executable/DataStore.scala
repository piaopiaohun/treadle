// See LICENSE for license details.

package treadle.executable

import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.JsonDSL._
import treadle.ScalaBlackBox

import scala.collection.mutable

/**
  * Creates a data store for the three underlying data types.
  * The numberOfBuffers is used to control the ability to rollback execution.
  * The meaning of the values of each slot must be maintained outside of this class.
  * This class only supports (2 ** 31) - 1 of any ints, longs or bigs.
  *
  * @param numberOfBuffers Number of buffers
  */
//scalastyle:off number.of.methods
class DataStore(val numberOfBuffers: Int, dataStoreAllocator: DataStoreAllocator)
extends HasDataArrays {
  assert(numberOfBuffers >= 0, s"DataStore: numberOfBuffers $numberOfBuffers must be >= 0")

  var leanMode      : Boolean = true
  val plugins       : mutable.HashMap[String, DataStorePlugin] = new mutable.HashMap()
  val activePlugins : mutable.ArrayBuffer[DataStorePlugin]     = new mutable.ArrayBuffer()

  def addPlugin(name: String, plugin: DataStorePlugin, enable: Boolean): Unit = {
    if(plugins.contains(name)) {
      throw TreadleException(s"Attempt to add already loaded plugin $name new $plugin, existing ${plugins(name)}")
    }
    plugins(name) = plugin
    plugin.setEnabled(enable)
  }

  def enablePlugin(name: String): Unit = {
    if(plugins.contains(name)) {
      println(s"Could not find plugin $name to remove it")
    }
    plugins(name).setEnabled(true)
  }

  def disablePlugin(name: String): Unit = {
    if(plugins.contains(name)) {
      println(s"Could not find plugin $name to remove it")
    }
    plugins(name).setEnabled(false)
  }

  def removePlugin(name: String): Unit = {
    if(plugins.contains(name)) {
      println(s"Could not find plugin $name to remove it")
    }
    val plugin = plugins(name)
    plugin.setEnabled(false)  // remove from active and should return to lean mode if no other plugins are active
    plugins.remove(name)
  }

  def hasEnabledPlugins: Boolean = {
    activePlugins.nonEmpty
  }

  var executionEngineOption: Option[ExecutionEngine] = None

  def setExecutionEngine(executionEngine: ExecutionEngine): Unit = {
    executionEngineOption = Some(executionEngine)

    executionEngine.optionsManager.treadleOptions.symbolsToWatch.foreach { symbolName =>
      if (executionEngine.symbolTable.contains(symbolName)) {
        watchList += executionEngine.symbolTable(symbolName)
      }
      else {
        throw TreadleException(s"treadleOptions.symbols to watch has bad symbolName $symbolName")
      }
    }

    setAssignmentDisplayModes()
  }

  def setAssignmentDisplayModes(): Unit = {
    executionEngineOption.foreach { executionEngine =>
      val watchList = executionEngine.optionsManager.treadleOptions.symbolsToWatch.map { symbolName =>
        executionEngine.symbolTable.get(symbolName) match {
          case Some(symbol) =>
            symbol
          case _ =>
            throw TreadleException(s"treadleOptions.symbols to watch has bad symbolName $symbolName")
        }
      }

      val verbose = executionEngineOption.get.verbose
      executionEngine.scheduler.combinationalAssigns.foreach { assigner =>
        val render = watchList.contains(assigner.symbol)
        assigner.setLeanMode(!verbose && !render)
        assigner.setVerbose(verbose)
        assigner.setRender(render)
      }
    }
  }

  def numberOfInts: Int  = dataStoreAllocator.nextIndexFor(IntSize)
  def numberOfLongs: Int = dataStoreAllocator.nextIndexFor(LongSize)
  def numberOfBigs: Int  = dataStoreAllocator.nextIndexFor(BigSize)

  val watchList: mutable.HashSet[Symbol] = new mutable.HashSet()

  val intData:   Array[Int]  = Array.fill(numberOfInts)(0)
  val longData:  Array[Long] = Array.fill(numberOfLongs)(0L)
  val bigData:   Array[Big]  = Array.fill(numberOfBigs)(Big(0))

  val rollBackBufferManager = new RollBackBufferManager(this)
  def saveData(clockName: String, time: Long): Unit = {
    if(numberOfBuffers > 0) {
      rollBackBufferManager.saveData(clockName, time)
    }
  }

  def runPlugins(symbol: Symbol, offset: Int = -1): Unit = {
    activePlugins.foreach { _.run(symbol, offset) }
  }

  def showAssignment(symbol: Symbol): Unit = {
    val showValue = symbol.normalize(apply(symbol))
    println(s"${symbol.name} <= $showValue h${showValue.toString(16)}")
  }

  def showIndirectAssignment(symbol: Symbol, value: BigInt, index: Int): Unit = {
    //TODO (chick) Need to build in display of index computation
    val showValue = symbol.normalize(value)
    println(s"${symbol.name}($index) <= $showValue")
  }

  def renderAssignment(symbol: Symbol): Unit = {
    executionEngineOption.foreach { executionEngine =>
      println(executionEngine.renderComputation(symbol.name))
    }
  }

  def getRegisterLastValueIndex(symbol: Symbol): Int = {
    executionEngineOption match {
      case Some(executionEngine) =>
        executionEngine.symbolTable(SymbolTable.makeLastValueName(symbol)).index
      case _ =>
        throw TreadleException(s"Could not find clock last value index for $symbol")
    }
  }

  case class GetInt(index: Int) extends IntExpressionResult {
    def apply(): Int = intData(index)
  }

  case class AssignInt(symbol: Symbol, expression: FuncInt) extends Assigner {
    val index: Int = symbol.index

    def runLean(): Unit = {intData(index) = expression() }

    def runFull(): Unit = {
      val value = expression()
      intData(index) = value
      runPlugins(symbol)
    }

    override def setLeanMode(isLean: Boolean): Unit = {
      run = if(isLean) runLean else runFull
    }
    var run: FuncUnit = runLean
  }

  case class TriggerConstantAssigner(symbol: Symbol, scheduler: Scheduler, triggerOnValue: Int = -1) extends Assigner {

    val index: Int = symbol.index

    var value: Int = 0

    def runLean(): Unit = {
      intData(index) = value
      if(value == triggerOnValue) {
        scheduler.executeTriggeredAssigns(symbol)
      }
      else {
        scheduler.executeTriggeredUnassigns(symbol)
      }
    }

    def runFull(): Unit = {
      intData(index) = value
      if(value == triggerOnValue) {
        if(isVerbose) println(s"===> Starting triggered assigns for $symbol")
        scheduler.executeTriggeredAssigns(symbol)
        if(isVerbose) println(s"===> Finished triggered assigns for $symbol")
      }
      else {
        if(isVerbose) println(s"===> Starting triggered assigns for $symbol")
        scheduler.executeTriggeredUnassigns(symbol)
        if(isVerbose) println(s"===> Finished triggered assigns for $symbol")
      }
      runPlugins(symbol)
    }

    override def setLeanMode(isLean: Boolean): Unit = {
      run = if(isLean) runLean else runFull
    }
    var run: FuncUnit = runLean
  }

  case class TriggerExpressionAssigner(
    symbol: Symbol,
    scheduler: Scheduler,
    expression: FuncInt,
    triggerOnValue: Int = -1
  ) extends Assigner {

    val index: Int = symbol.index

    def runLean(): Unit = {
      val value = expression()
      intData(index) = value
      if(value == triggerOnValue) {
        scheduler.executeTriggeredAssigns(symbol)
      }
      else {
        scheduler.executeTriggeredUnassigns(symbol)
      }
    }

    def runFull(): Unit = {
      val value = expression()
      intData(index) = value
      if(value == triggerOnValue) {
        if(isVerbose) println(s"===> Starting triggered assigns for $symbol")
        scheduler.executeTriggeredAssigns(symbol)
        if(isVerbose) println(s"===> Finished triggered assigns for $symbol")
      }
      else {
        if(isVerbose) println(s"===> Starting triggered assigns for $symbol")
        scheduler.executeTriggeredUnassigns(symbol)
        if(isVerbose) println(s"===> Finished triggered assigns for $symbol")
      }
      runPlugins(symbol)
    }

    override def setLeanMode(isLean: Boolean): Unit = {
      run = if(isLean) runLean else runFull
    }
    var run: FuncUnit = runLean
  }

  case class GetLong(index: Int) extends LongExpressionResult {
    def apply(): Long = longData(index)
  }

  case class AssignLong(symbol: Symbol, expression: FuncLong) extends Assigner {
    val index: Int = symbol.index

    def runLean(): Unit = {
      longData(index) = expression()
    }

    def runFull(): Unit = {
      val value = expression()
      longData(index) = value
      runPlugins(symbol)
    }

    override def setLeanMode(isLean: Boolean): Unit = {
      run = if(isLean) {
        runLean
      } else {
        runFull
      }
    }
    var run: FuncUnit = runLean
  }

  case class GetBig(index: Int) extends BigExpressionResult {
    def apply(): Big = bigData(index)
  }

  case class AssignBig(symbol: Symbol, expression: FuncBig) extends Assigner {
    val index: Int = symbol.index

    def runLean(): Unit = {
      bigData(index) = expression()
    }
    def runFull(): Unit = {
      val value = expression()
      bigData(index) = value
      runPlugins(symbol)
    }

    override def setLeanMode(isLean: Boolean): Unit = {
      run = if(isLean) runLean else runFull
    }
    var run: FuncUnit = runLean
  }

  /** for memory implementations */
  case class GetIntIndirect(
                             memorySymbol: Symbol,
                             getMemoryIndex: FuncInt,
                             enable: FuncInt
                           ) extends IntExpressionResult {
    val memoryLocation: Int = memorySymbol.index
    def apply(): Int = {
      intData(memoryLocation + (getMemoryIndex() % memorySymbol.slots))
    }
  }

  case class GetLongIndirect(
                             memorySymbol: Symbol,
                             getMemoryIndex: FuncInt,
                             enable: FuncInt
                           ) extends LongExpressionResult {
    val memoryLocation: Int = memorySymbol.index
    def apply(): Long = {
      longData(memoryLocation + (getMemoryIndex() % memorySymbol.slots))
    }
  }

  case class GetBigIndirect(
                             memorySymbol: Symbol,
                             getMemoryIndex: FuncInt,
                             enable: FuncInt
                           ) extends BigExpressionResult {
    val memoryLocation: Int = memorySymbol.index
    def apply(): Big = {
      bigData(memoryLocation + (getMemoryIndex() % memorySymbol.slots))
    }
  }

  case class AssignIntIndirect(
                               symbol: Symbol,
                               memorySymbol: Symbol,
                               getMemoryIndex: FuncInt,
                               enable: FuncInt,
                               expression: FuncInt
                              ) extends Assigner {
    val index: Int = memorySymbol.index

    def runLean(): Unit = {
      if(enable() > 0) {
        intData(index + getMemoryIndex.apply()) = expression()
      }
    }

    def runFull(): Unit = {
      if(enable() > 0) {
        val value = expression()
        val memoryIndex = getMemoryIndex.apply()
        intData(index + (memoryIndex % memorySymbol.slots)) = value
        runPlugins(memorySymbol, memoryIndex)
      }
    }

    override def setLeanMode(isLean: Boolean): Unit = {
      run = if(isLean) runLean else runFull
    }
    var run: FuncUnit = runLean
  }

  case class AssignLongIndirect(
                               symbol: Symbol,
                               memorySymbol: Symbol,
                               getMemoryIndex: FuncInt,
                               enable: FuncInt,
                               expression: FuncLong
                              ) extends Assigner {
    val index: Int = memorySymbol.index

    def runLean(): Unit = {
      if(enable() > 0) {
        longData(index + (getMemoryIndex.apply() % memorySymbol.slots)) = expression()
      }
    }

    def runFull(): Unit = {
      if(enable() > 0) {
        val value = expression()
        val memoryIndex = getMemoryIndex.apply()
        longData(index + (memoryIndex % memorySymbol.slots)) = value
        runPlugins(memorySymbol, memoryIndex)
      }
    }

    override def setLeanMode(isLean: Boolean): Unit = {
      run = if(isLean) runLean else runFull
    }
    var run: FuncUnit = runLean
  }

  case class AssignBigIndirect(
                                 symbol: Symbol,
                                 memorySymbol: Symbol,
                                 getMemoryIndex: FuncInt,
                                 enable: FuncInt,
                                 expression: FuncBig
                               ) extends Assigner {
    val index: Int = memorySymbol.index

    def runLean(): Unit = {
      if(enable() > 0) {
        bigData(index + (getMemoryIndex.apply() % memorySymbol.slots)) = expression()
      }
    }

    def runFull(): Unit = {
      if(enable() > 0) {
        val value = expression()
        val memoryIndex = getMemoryIndex.apply()
        bigData(index + (memoryIndex % memorySymbol.slots)) = value
        runPlugins(memorySymbol, memoryIndex)
      }
    }

    override def setLeanMode(isLean: Boolean): Unit = {
      run = if(isLean) runLean else runFull
    }
    var run: FuncUnit = runLean
  }

  case class BlackBoxShim(
      unexpandedName: String,
      outputName:     Symbol,
      inputs:         Seq[Symbol],
      implementation: ScalaBlackBox
  )
  extends BigExpressionResult {

    val dataStore: DataStore = DataStore.this

    def apply(): Big = {
      val inputValues = inputs.map { input => dataStore(input) }
      val bigInt = implementation.getOutput(inputValues, outputName.firrtlType, unexpandedName)
      bigInt
    }
  }

  def apply(symbol: Symbol): Big = {
    symbol.dataSize match {
      case IntSize  => intData(symbol.index)
      case LongSize => longData(symbol.index)
      case BigSize  => bigData(symbol.index)
    }
  }

  def apply(symbol: Symbol, offset: Int): Big = {
    symbol.dataSize match {
      case IntSize  => intData(symbol.index + offset)
      case LongSize => longData(symbol.index + offset)
      case BigSize  => bigData(symbol.index + offset)
    }
  }

  def getWaveformValues(symbols: Array[Symbol],
                        cycleTime: Int,
                        windowSize : Int): Option[WaveformValues] = {
    val clockName = "clk" // TODO: how to handle for multiclock?
    val rollbackRing = rollBackBufferManager.clockToBuffers(clockName)

    var buffers : Seq[RollBackBuffer] = rollbackRing.newestToOldestBuffers.reverse

//    println(s"cycleTime $cycleTime, buffer length: ${buffers.length}")

    if (cycleTime >= buffers.length) {
      None
    } else {
      val leftIndexInclusive = 0.max(cycleTime + 1 - (windowSize + 1) / 2)
      val rightIndexExclusive = buffers.length.min(cycleTime + windowSize / 2 + 1)
      val n = rightIndexExclusive - leftIndexInclusive

//      println(buffers.length)
      buffers = buffers.dropRight(buffers.length - rightIndexExclusive).drop(leftIndexInclusive)
//      println(buffers.length)
//      println(n)

      val clockValues = new Array[BigInt](n)
      val symbolValues = Array.ofDim[BigInt](symbols.length, n)

      buffers.zipWithIndex.foreach { case (buffer, i) =>
        clockValues(i) = buffer.time
        symbols.zipWithIndex.foreach { case (symbol, j) =>
          symbol.dataSize match {
            case IntSize => symbolValues(j)(i) = buffer.intData(symbol.index)
            case LongSize => symbolValues(j)(i) = buffer.longData(symbol.index)
            case BigSize => symbolValues(j)(i) = buffer.bigData(symbol.index)
          }
//          println(symbolValues(i).mkString(" "))
        }
      }
      println(clockValues.mkString(" "))
      Some(WaveformValues(clockValues, symbols, symbolValues))
    }
  }

  def update(symbol: Symbol, value: Big): Unit = {
    symbol.dataSize match {
      case IntSize  => intData(symbol.index) = value.toInt
      case LongSize => longData(symbol.index) = value.toLong
      case BigSize  => bigData(symbol.index) = value
    }
  }

  //scalastyle:off cyclomatic.complexity method.length
  def serialize: String = {

    val nextForData = Seq(IntSize, LongSize, BigSize).map { size =>
      size.toString -> dataStoreAllocator.nextIndexFor(size)
    }.toMap

    def toIntJArray(array : Array[Int])  = JArray(array.toList.map { a ⇒ val v: JValue = a; v })
    def toLongJArray(array: Array[Long]) = JArray(array.toList.map { a ⇒ val v: JValue = a; v })
    def toBigJArray(array : Array[Big])  = JArray(array.toList.map { a ⇒ val v: JValue = a; v })

    val intDataValues  = toIntJArray(intData)
    val longDataValues = toLongJArray(longData)
    val bigDataValues  = toBigJArray(bigData)

    def packageRollbackBuffers = {
      val packet = rollBackBufferManager.clockToBuffers.keys.toList.sorted.map { clockName =>
        val rollbackRing = rollBackBufferManager.clockToBuffers(clockName)

        val intArray  = JArray(rollbackRing.ringBuffer.map { x => toIntJArray(x.intData)   }.toList)
        val longArray = JArray(rollbackRing.ringBuffer.map { x => toLongJArray(x.longData) }.toList)
        val bigArray  = JArray(rollbackRing.ringBuffer.map { x => toBigJArray(x.bigData)   }.toList)

        ("clockName" -> clockName) ~
                ("latestBufferIndex" -> rollbackRing.latestBufferIndex) ~
                ("oldestBufferIndex" -> rollbackRing.oldestBufferIndex) ~
                ("intBuffers"        -> intArray) ~
                ("longBuffers"       -> longArray) ~
                ("bigBuffers"        -> bigArray)
      }

      packet
    }

    val json =
          ("numberOfBuffers" -> numberOfBuffers) ~
          ("nextForData"     -> nextForData) ~
          ("intData"         -> intDataValues) ~
          ("longData"        -> longDataValues) ~
          ("bigData"         -> bigDataValues) ~
          ("rollbackData"    -> packageRollbackBuffers)

    pretty(render(json))
  }

  def deserialize(jsonString: String): Unit = {
    val json2 = parse(jsonString)

    for {
      JObject(child) <- json2
      JField(fieldName, value) <- child
    } {
      fieldName match {
        case "numberOfBuffers" =>
          val JInt(numBuffs) = value
          if(numBuffs != numberOfBuffers) {
            println(s"WARNING: numberOfBuffers in snapshot $numBuffs does not match runtime $numberOfBuffers")
          }
        case "nextForData" =>
          for {
            JObject(child2) <- value
            JField(fieldName, value) <- child2
          } {
            val JInt(nextNumber) = value
            fieldName match {
              case "Int"  => dataStoreAllocator.nextIndexFor(IntSize)  = nextNumber.toInt
              case "Long" => dataStoreAllocator.nextIndexFor(LongSize) = nextNumber.toInt
              case "Big"  => dataStoreAllocator.nextIndexFor(BigSize)  = nextNumber.toInt
            }
          }
        case "intData" =>
          value match  {
            case JArray(elementValues) =>
              elementValues.zipWithIndex.foreach {
                case (JInt(v), index) => intData(index) = v.toInt
                case _ => None
              }
            case _ =>
          }
        case "longData" =>
          value match  {
            case JArray(elementValues) =>
              elementValues.zipWithIndex.foreach {
                case (JInt(v), index) => longData(index) = v.toLong
                case _ => None
              }
            case _ =>
          }
        case "bigData" =>
          value match  {
            case JArray(elementValues) =>
              elementValues.zipWithIndex.foreach {
                case (JInt(v), index) => bigData(index) = v
                case _ => None
              }
            case _ =>
          }
        case "rollbackData" =>
          var clockBuffer = rollBackBufferManager.clockToBuffers.values.head
          value match {
            case JArray(clockSections) =>
              for {
                JObject(child2) <- clockSections
                JField(subFieldName, subValue) <- child2
              } {
                (subFieldName, subValue) match {
                  case ("clockName", JString(clockName)) =>
                    assert(rollBackBufferManager.clockToBuffers.contains(clockName))
                    clockBuffer = rollBackBufferManager.clockToBuffers(clockName)

                  case ("latestBufferIndex", JInt(latestBufferIndex)) =>
                    clockBuffer.latestBufferIndex = latestBufferIndex.toInt

                  case ("oldestBufferIndex", JInt(oldestBufferIndex)) =>
                    clockBuffer.oldestBufferIndex = oldestBufferIndex.toInt

                  case ("intBuffers", JArray(numArrays)) =>
                    numArrays.zipWithIndex.foreach {
                      case (JArray(elementValues), rollbackIndex) =>
                        elementValues.zipWithIndex.foreach {
                          case (JInt(v), index) =>
                            clockBuffer.ringBuffer(rollbackIndex).intData(index) = v.toInt
                          case _ => None
                        }
                      case _ =>
                    }

                  case ("longBuffers", JArray(numArrays)) =>
                    numArrays.zipWithIndex.foreach {
                      case (JArray(elementValues), rollbackIndex) =>
                        elementValues.zipWithIndex.foreach {
                          case (JInt(v), index) =>
                            clockBuffer.ringBuffer(rollbackIndex).longData(index) = v.toLong
                          case _ => None
                        }
                      case _ =>
                    }

                  case ("bigBuffers", JArray(numArrays)) =>
                    numArrays.zipWithIndex.foreach {
                      case (JArray(elementValues), rollbackIndex) =>
                        elementValues.zipWithIndex.foreach {
                          case (JInt(v), index) =>
                            clockBuffer.ringBuffer(rollbackIndex).bigData(index) = v
                          case _ => None
                        }
                      case _ =>
                    }

                  case (subSubFieldName, subSubValue) =>
                    println(s"got an unhandled field in clock buffer section $subSubFieldName => $subSubValue")
                }
              }

            case _ =>
          }

        case _ =>
          // println(s"$fieldName -> $value")
      }
    }
  }
}

object DataStore {
  def apply(numberOfBuffers: Int, dataStoreAllocator: DataStoreAllocator): DataStore = {
    new DataStore(numberOfBuffers, dataStoreAllocator)
  }
}

trait HasDataArrays {
  def intData  : Array[Int]
  def longData : Array[Long]
  def bigData  : Array[Big]

  def setValueAtIndex(dataSize: DataSize, index: Int, value: Big): Unit = {
    dataSize match {
      case IntSize  => intData(index)  = value.toInt
      case LongSize => longData(index) = value.toLong
      case BigSize  => bigData(index)  = value
    }
  }

  def getValueAtIndex(dataSize: DataSize, index: Int): BigInt = {
    dataSize match {
      case IntSize  => intData(index)
      case LongSize => longData(index)
      case BigSize  => bigData(index)
    }
  }
}

class DataStoreAllocator {
  val nextIndexFor = new mutable.HashMap[DataSize, Int]

  nextIndexFor(IntSize)  = 0
  nextIndexFor(LongSize) = 0
  nextIndexFor(BigSize)  = 0

  def numberOfInts: Int  = nextIndexFor(IntSize)
  def numberOfLongs: Int = nextIndexFor(LongSize)
  def numberOfBigs: Int  = nextIndexFor(BigSize)

  val watchList: mutable.HashSet[Symbol] = new mutable.HashSet()

  def getSizes: (Int, Int, Int) = {
    (nextIndexFor(IntSize), nextIndexFor(LongSize), nextIndexFor(BigSize))
  }

  def getIndex(dataSize: DataSize, slots: Int = 1): Int = {
    val index = nextIndexFor(dataSize)
    nextIndexFor(dataSize) += slots
    index
  }
}