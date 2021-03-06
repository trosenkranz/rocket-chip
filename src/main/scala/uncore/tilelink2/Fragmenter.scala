// See LICENSE for license details.

package uncore.tilelink2

import Chisel._
import chisel3.internal.sourceinfo.SourceInfo
import diplomacy._
import scala.math.{min,max}

// minSize: minimum size of transfers supported by all outward managers
// maxSize: maximum size of transfers supported after the Fragmenter is applied
// alwaysMin: fragment all requests down to minSize (else fragment to maximum supported by manager)
// Fragmenter modifies: PutFull, PutPartial, LogicalData, Get, Hint
// Fragmenter passes: ArithmeticData (truncated to minSize if alwaysMin)
// Fragmenter breaks: Acquire (and thus cuts BCE channels)
class TLFragmenter(minSize: Int, maxSize: Int, alwaysMin: Boolean = false) extends LazyModule
{
  require (isPow2 (maxSize))
  require (isPow2 (minSize))
  require (minSize < maxSize)

  val fragmentBits = log2Ceil(maxSize / minSize)

  def expandTransfer(x: TransferSizes) = if (!x) x else {
    require (x.max >= minSize) // validate that we can apply the fragmenter correctly
    TransferSizes(x.min, maxSize)
  }
  def shrinkTransfer(x: TransferSizes) =
    if (!alwaysMin) x else
    if (x.min <= minSize) TransferSizes(x.min, min(minSize, x.max)) else
    TransferSizes.none
  def mapManager(m: TLManagerParameters) = m.copy(
    supportsAcquire    = TransferSizes.none, // this adapter breaks acquires
    supportsArithmetic = shrinkTransfer(m.supportsArithmetic),
    supportsLogical    = expandTransfer(m.supportsLogical),
    supportsGet        = expandTransfer(m.supportsGet),
    supportsPutFull    = expandTransfer(m.supportsPutFull),
    supportsPutPartial = expandTransfer(m.supportsPutPartial),
    supportsHint       = expandTransfer(m.supportsHint))
  def mapClient(c: TLClientParameters) = c.copy(
    sourceId = IdRange(c.sourceId.start << fragmentBits, c.sourceId.end << fragmentBits),
    // since we break Acquires, none of these work either:
    supportsProbe      = TransferSizes.none,
    supportsArithmetic = TransferSizes.none,
    supportsLogical    = TransferSizes.none,
    supportsGet        = TransferSizes.none,
    supportsPutFull    = TransferSizes.none,
    supportsPutPartial = TransferSizes.none,
    supportsHint       = TransferSizes.none)

  // Because the Fragmenter stalls inner A while serving outer, it can wipe away inner latency
  val node = TLAdapterNode(
    clientFn  = { case Seq(c) => c.copy(clients = c.clients.map(mapClient)) },
    managerFn = { case Seq(m) => m.copy(managers = m.managers.map(mapManager), minLatency = 0) })

  lazy val module = new LazyModuleImp(this) {
    val io = new Bundle {
      val in  = node.bundleIn
      val out = node.bundleOut
    }

    // All managers must share a common FIFO domain (responses might end up interleaved)
    val edgeOut   = node.edgesOut(0)
    val edgeIn    = node.edgesIn(0)
    val manager   = edgeOut.manager
    val managers  = manager.managers
    val beatBytes = manager.beatBytes
    val fifoId = managers(0).fifoId
    require (fifoId.isDefined && managers.map(_.fifoId == fifoId).reduce(_ && _))

    // We don't support fragmenting to sub-beat accesses
    require (minSize >= beatBytes)

    /* The Fragmenter is a bit tricky, because there are 5 sizes in play:
     *   max  size -- the maximum transfer size possible
     *   orig size -- the original pre-fragmenter size
     *   frag size -- the modified post-fragmenter size
     *   min  size -- the threshold below which frag=orig
     *   beat size -- the amount transfered on any given beat
     *
     * The relationships are as follows:
     *   max >= orig >= frag
     *   max >  min  >= beat
     * It IS possible that orig <= min (then frag=orig; ie: no fragmentation)
     *
     * The fragment# (sent via TL.source) is measured in multiples of min size.
     * Meanwhile, to track the progress, counters measure in multiples of beat size.
     *
     * Here is an example of a bus with max=256, min=8, beat=4 and a device supporting 16.
     *
     * in.A    out.A (frag#)  out.D (frag#)  in.D     gen# ack#
     * get64   get16  6       ackD16  6      ackD64    12   15
     *                        ackD16  6      ackD64         14
     *                        ackD16  6      ackD64         13
     *                        ackD16  6      ackD64         12
     *         get16  4       ackD16  4      ackD64    8    11
     *                        ackD16  4      ackD64         10
     *                        ackD16  4      ackD64         9
     *                        ackD16  4      ackD64         8
     *         get16  2       ackD16  2      ackD64    4    7
     *                        ackD16  2      ackD64         6
     *                        ackD16  2      ackD64         5
     *                        ackD16  2      ackD64         4
     *         get16  0       ackD16  0      ackD64    0    3
     *                        ackD16  0      ackD64         2
     *                        ackD16  0      ackD64         1
     *                        ackD16  0      ackD64         0
     *
     * get8    get8   0       ackD8   0      ackD8     0    1
     *                        ackD8   0      ackD8          0
     *
     * get4    get4   0       ackD4   0      ackD4     0    0
     * get1    get1   0       ackD1   0      ackD1     0    0
     *
     * put64   put16  6                                15   
     * put64   put16  6                                14
     * put64   put16  6                                13
     * put64   put16  6       ack16   6                12    12
     * put64   put16  4                                11
     * put64   put16  4                                10
     * put64   put16  4                                9
     * put64   put16  4       ack16   4                8     8
     * put64   put16  2                                7
     * put64   put16  2                                6
     * put64   put16  2                                5
     * put64   put16  2       ack16   2                4     4
     * put64   put16  0                                3
     * put64   put16  0                                2
     * put64   put16  0                                1
     * put64   put16  0       ack16   0      ack64     0     0
     *
     * put8    put8   0                                1
     * put8    put8   0       ack8    0      ack8      0     0
     *
     * put4    put4   0       ack4    0      ack4      0     0
     * put1    put1   0       ack1    0      ack1      0     0
     */

    val in = io.in(0)
    val out = io.out(0)

    val counterBits = log2Up(maxSize/beatBytes)
    val maxDownSize = if (alwaysMin) minSize else manager.maxTransfer

    // First, handle the return path
    val acknum = RegInit(UInt(0, width = counterBits))
    val dOrig = Reg(UInt())
    val dFragnum = out.d.bits.source(fragmentBits-1, 0)
    val dFirst = acknum === UInt(0)
    val dsizeOH  = UIntToOH (out.d.bits.size, log2Ceil(maxDownSize)+1)
    val dsizeOH1 = UIntToOH1(out.d.bits.size, log2Up(maxDownSize))
    val dHasData = edgeOut.hasData(out.d.bits)

    // calculate new acknum
    val acknum_fragment = dFragnum << log2Ceil(minSize/beatBytes)
    val acknum_size = dsizeOH1 >> log2Ceil(beatBytes)
    assert (!out.d.valid || (acknum_fragment & acknum_size) === UInt(0))
    val dFirst_acknum = acknum_fragment | Mux(dHasData, acknum_size, UInt(0))
    val ack_decrement = Mux(dHasData, UInt(1), dsizeOH >> log2Ceil(beatBytes))
    // calculate the original size
    val dFirst_size = OH1ToUInt((dFragnum << log2Ceil(minSize)) | dsizeOH1)

    when (out.d.fire()) {
      acknum := Mux(dFirst, dFirst_acknum, acknum - ack_decrement)
      when (dFirst) { dOrig := dFirst_size }
    }

    // Swallow up non-data ack fragments
    val drop = !dHasData && (dFragnum =/= UInt(0))
    out.d.ready := in.d.ready || drop
    in.d.valid  := out.d.valid && !drop
    in.d.bits   := out.d.bits // pass most stuff unchanged
    in.d.bits.addr_lo := out.d.bits.addr_lo & ~dsizeOH1
    in.d.bits.source := out.d.bits.source >> fragmentBits
    in.d.bits.size   := Mux(dFirst, dFirst_size, dOrig)

    // What maximum transfer sizes do downstream devices support?
    val maxArithmetics = managers.map(_.supportsArithmetic.max)
    val maxLogicals    = managers.map(_.supportsLogical.max)
    val maxGets        = managers.map(_.supportsGet.max)
    val maxPutFulls    = managers.map(_.supportsPutFull.max)
    val maxPutPartials = managers.map(_.supportsPutPartial.max)
    val maxHints       = managers.map(m => if (m.supportsHint) maxDownSize else 0)

    // We assume that the request is valid => size 0 is impossible
    val lgMinSize = UInt(log2Ceil(minSize))
    val maxLgArithmetics = maxArithmetics.map(m => if (m == 0) lgMinSize else UInt(log2Ceil(m)))
    val maxLgLogicals    = maxLogicals   .map(m => if (m == 0) lgMinSize else UInt(log2Ceil(m)))
    val maxLgGets        = maxGets       .map(m => if (m == 0) lgMinSize else UInt(log2Ceil(m)))
    val maxLgPutFulls    = maxPutFulls   .map(m => if (m == 0) lgMinSize else UInt(log2Ceil(m)))
    val maxLgPutPartials = maxPutPartials.map(m => if (m == 0) lgMinSize else UInt(log2Ceil(m)))
    val maxLgHints       = maxHints      .map(m => if (m == 0) lgMinSize else UInt(log2Ceil(m)))

    // If this is infront of a single manager, these become constants
    val find = manager.findFast(edgeIn.address(in.a.bits))
    val maxLgArithmetic  = Mux1H(find, maxLgArithmetics)
    val maxLgLogical     = Mux1H(find, maxLgLogicals)
    val maxLgGet         = Mux1H(find, maxLgGets)
    val maxLgPutFull     = Mux1H(find, maxLgPutFulls)
    val maxLgPutPartial  = Mux1H(find, maxLgPutPartials)
    val maxLgHint        = Mux1H(find, maxLgHints)

    val limit = if (alwaysMin) lgMinSize else 
      MuxLookup(in.a.bits.opcode, lgMinSize, Array(
        TLMessages.PutFullData    -> maxLgPutFull,
        TLMessages.PutPartialData -> maxLgPutPartial,
        TLMessages.ArithmeticData -> maxLgArithmetic,
        TLMessages.LogicalData    -> maxLgLogical,
        TLMessages.Get            -> maxLgGet,
        TLMessages.Hint           -> maxLgHint))

    val aOrig = in.a.bits.size
    val aFrag = Mux(aOrig > limit, limit, aOrig)
    val aOrigOH1 = UIntToOH1(aOrig, log2Ceil(maxSize))
    val aFragOH1 = UIntToOH1(aFrag, log2Up(maxDownSize))
    val aHasData = node.edgesIn(0).hasData(in.a.bits)
    val aMask = Mux(aHasData, UInt(0), aFragOH1)

    val gennum = RegInit(UInt(0, width = counterBits))
    val aFirst = gennum === UInt(0)
    val old_gennum1 = Mux(aFirst, aOrigOH1 >> log2Ceil(beatBytes), gennum - UInt(1))
    val new_gennum = ~(~old_gennum1 | (aMask >> log2Ceil(beatBytes))) // ~(~x|y) is width safe
    val aFragnum = ~(~(old_gennum1 >> log2Ceil(minSize/beatBytes)) | (aFragOH1 >> log2Ceil(minSize)))

    when (out.a.fire()) { gennum := new_gennum }

    val delay = !aHasData && aFragnum =/= UInt(0)
    out.a.valid := in.a.valid
    in.a.ready := out.a.ready && !delay
    out.a.bits := in.a.bits
    out.a.bits.addr_hi := in.a.bits.addr_hi | (~aFragnum << log2Ceil(minSize/beatBytes) & aOrigOH1 >> log2Ceil(beatBytes))
    out.a.bits.source := Cat(in.a.bits.source, aFragnum)
    out.a.bits.size := aFrag

    // Tie off unused channels
    in.b.valid := Bool(false)
    in.c.ready := Bool(true)
    in.e.ready := Bool(true)
    out.b.ready := Bool(true)
    out.c.valid := Bool(false)
    out.e.valid := Bool(false)
  }
}

object TLFragmenter
{
  // applied to the TL source node; y.node := TLFragmenter(x.node, 256, 4)
  def apply(minSize: Int, maxSize: Int, alwaysMin: Boolean = false)(x: TLOutwardNode)(implicit sourceInfo: SourceInfo): TLOutwardNode = {
    val fragmenter = LazyModule(new TLFragmenter(minSize, maxSize, alwaysMin))
    fragmenter.node := x
    fragmenter.node
  }
}

/** Synthesizeable unit tests */
import unittest._

class TLRAMFragmenter(ramBeatBytes: Int, maxSize: Int) extends LazyModule {
  val fuzz = LazyModule(new TLFuzzer(5000))
  val model = LazyModule(new TLRAMModel)
  val ram  = LazyModule(new TLRAM(AddressSet(0x0, 0x3ff), beatBytes = ramBeatBytes))

  model.node := fuzz.node
  ram.node := TLFragmenter(ramBeatBytes, maxSize)(model.node)

  lazy val module = new LazyModuleImp(this) with HasUnitTestIO {
    io.finished := fuzz.module.io.finished
  }
}

class TLRAMFragmenterTest(ramBeatBytes: Int, maxSize: Int) extends UnitTest(timeout = 500000) {
  io.finished := Module(LazyModule(new TLRAMFragmenter(ramBeatBytes,maxSize)).module).io.finished
}

