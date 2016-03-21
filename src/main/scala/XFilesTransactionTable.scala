// See LICENSE for license details

package xfiles

import Chisel._
import rocket.{RoCCCommand, RoCCResponse, RoCCInterface}
import cde.{Parameters, Field}

case object TransactionTableQueueSize extends Field[Int]

trait FlagsVDIO {
  val valid = Bool()  // Eligible for scheduling on the backend
  val done = Bool()   // Entry is finished
  val input = Bool()  // Asserted when waiting for input queue to be not empty
  val output = Bool() // Asserted when waiting for output queue to be not full
}

trait TableRVDIO extends XFilesParameters {
  val flags = new Bundle with FlagsVDIO {
    val reserved = Bool() // Entry is in use
  }
  val asid = UInt(width = asidWidth)
  val tid = UInt(width = tidWidth)

  def reset() {
    this.flags.valid := Bool(false)
    this.flags.reserved := Bool(false)
  }
  def reserve(asid: UInt, tid: UInt) {
    this.flags.reserved := Bool(true)
    this.flags.valid := Bool(false)
    this.flags.done := Bool(false)
    this.flags.input := Bool(false)
    this.flags.output := Bool(false)
    this.asid := asid
    this.tid := tid
  }
}

class TableEntry(implicit p: Parameters) extends XFilesBundle()(p)
    with TableRVDIO {
  aliasList += ( "flags.reserved" -> "R", "flags.valid" -> "V",
    "flags.done" -> "D", "flags.input" -> "I", "flags.output" -> "O",
    "asid" -> "ASID", "tid" -> "TID")
}

trait HasTable {
  def isFree[T <: TableEntry](x: T): Bool = { !x.flags.reserved }
  def findAsidTid[T <: TableEntry](x: T, asid: UInt, tid: UInt): Bool = {
    (x.asid === asid) & (x.tid === tid) & (x.flags.valid | x.flags.reserved) }
}

class XFilesTransactionTableCmdResp(implicit p: Parameters) extends
    XFilesBundle()(p) {
  val cmd = Decoupled(new RoCCCommand).flip
  val resp = Decoupled(new RoCCResponse)
  val busy = Bool(OUTPUT)
  val regIdx = (new CoreIdx).flip
}

class RespBundle(implicit p: Parameters) extends XFilesBundle()(p) {
  val rocc = new RoCCResponse
  val idx = UInt(width = numCores)
}

class XFilesTransactionTable(implicit p: Parameters) extends XFilesModule()(p)
    with HasTable with XFilesResponseCodes {
  val io = new Bundle { // The portion of the RoCCInterface this uses
    val xfiles = new RoCCInterface
    val regIdx = (new CoreIdx).flip
    val memIdx = new CoreIdx
    val backend = (new XFilesBackendInterface).flip
    // val xfiles = new XFilesTransactionTableCmdResp
    // val backend = (new XFilesTransactionTableCmdResp).flip
  }
  val queueSize = p(TransactionTableQueueSize)

  val cmd = io.xfiles.cmd
  val funct = cmd.bits.inst.funct

  def getCmdAsid() = { cmd.bits.rs1(asidWidth + tidWidth - 1, tidWidth) }
  def getCmdTid() = { cmd.bits.rs1(tidWidth - 1, 0) }

  def getRespCode() = { io.backend.rocc.resp.bits.data(xLen - 1, xLen - respCodeWidth) }
  def getRespTid() = {
    val offset = xLen - respCodeWidth
    io.backend.rocc.resp.bits.data(offset - 1, offset - tidWidth) }
  def getRespAsid() = {
    val offset = xLen - respCodeWidth - tidWidth
    io.backend.rocc.resp.bits.data(offset - 1, offset - asidWidth) }

  val numEntries = transactionTableNumEntries

  val table = Reg(Vec(numEntries, new TableEntry))
  val queueInput = Vec.fill(numEntries){
    Module(new Queue(Vec(2, UInt(width = xLen)), queueSize)).io }
  val queueOutput = Vec.fill(numEntries){
    Module(new Queue(UInt(width = xLen), queueSize)).io }

  val hasFree = table.exists(isFree(_: TableEntry))
  val idxFree = table.indexWhere(isFree(_: TableEntry))

  val newRequest = cmd.fire() & funct === t_NEW_REQUEST
  val writeData = cmd.fire() & funct === t_WRITE_DATA
  val writeDataLast = cmd.fire() & funct === t_WRITE_DATA_LAST
  val readDataPoll = cmd.fire() & funct === t_READ_DATA
  val unknownCmd = cmd.fire() & !(
    newRequest | writeData | writeDataLast | readDataPoll )
  val asid =  getCmdAsid()
  val tid = getCmdTid()

  val hitAsidTid = table.exists(findAsidTid(_: TableEntry, asid, tid))
  val idxAsidTid = table.indexWhere(findAsidTid(_: TableEntry, asid, tid))

  // Temporary pass-through
  io.backend.rocc.cmd.valid := io.xfiles.cmd.valid & unknownCmd & hitAsidTid
  io.backend.rocc.cmd.bits := io.xfiles.cmd.bits
  io.xfiles.cmd.ready := io.backend.rocc.cmd.ready

  io.xfiles.busy := io.backend.rocc.busy

  // memory connections
  io.backend.rocc.mem <> io.xfiles.mem
  io.backend.memIdx <> io.memIdx
  // io.backned.rocc.autl <> io.xfiles.autl
  // io.backend.rocc.utl <> io.xfiles.utl
  // io.backend.rocc.fpu_req <> io.xfiles.fpu_req
  // io.backend.rocc.fpu_resp <> io.xfiles.fpu_resp
  // io.backend.rocc.excepction <> io.xfiles.exception

  // resp
  val resp_d = Reg(Valid(new RespBundle))
  io.xfiles.resp.valid := io.backend.rocc.resp.valid | resp_d.valid
  io.xfiles.resp.bits := io.backend.rocc.resp.bits
  io.backend.rocc.resp.ready := io.xfiles.resp.ready

  resp_d.valid := cmd.fire()
  resp_d.bits.rocc.rd := cmd.bits.inst.rd
  resp_d.bits.idx := io.regIdx.cmd

  // regIdx
  io.regIdx.resp := io.backend.regIdx.resp
  io.backend.regIdx.cmd := io.regIdx.cmd

  when (resp_d.valid) {
    io.xfiles.resp.bits.rd := resp_d.bits.rocc.rd
    io.xfiles.resp.bits.data := resp_d.bits.rocc.data
    io.regIdx.resp := resp_d.bits.idx
  }

  // Queue connections
  (0 until numEntries).map(i => {
    val hitNew = newRequest & hasFree & (idxFree === UInt(i))
    val hitOld = (writeData|writeDataLast) & hitAsidTid & idxAsidTid===UInt(i)
    val enq = hitNew | hitOld
    val deq = io.backend.queueIO.in.ready & io.backend.queueIO.tidx === UInt(i)
    queueInput(i).enq.valid := enq
    queueInput(i).enq.bits := Vec(cmd.bits.rs1, cmd.bits.rs2)

    queueInput(i).deq.ready := deq
  })
  io.backend.queueIO.in.bits := queueInput(io.backend.queueIO.tidx).deq.bits
  io.backend.queueIO.in.valid := queueInput(io.backend.queueIO.tidx).deq.valid

  (0 until numEntries).map(i => {
    val enq = io.backend.queueIO.out.valid & io.backend.queueIO.tidx===UInt(i)
    val deq = readDataPoll & hitAsidTid & (idxAsidTid === UInt(i))
    queueOutput(i).enq.valid := enq
    queueOutput(i).enq.bits := io.backend.queueIO.out.bits

    queueOutput(i).deq.ready := deq
  })
  io.backend.queueIO.out.ready := queueOutput(io.backend.queueIO.tidx).enq.ready

  // State updates. These are structured with the error response first
  // followed by a check on the correct condition, table updates (if
  // applicable), and the correct response.
  when (newRequest) {
    genResp(resp_d.bits.rocc.data, resp_TID, -SInt(err_XFILES_TTABLEFULL))
    when (hasFree) {
      genResp(resp_d.bits.rocc.data, resp_TID, tid)
      table(idxFree).reserve(asid, tid)
    }
  }

  when (writeData | writeDataLast) {
    genResp(resp_d.bits.rocc.data, resp_QUEUE_ERR, tid)
    when (queueInput(idxAsidTid).enq.ready) {
      genResp(resp_d.bits.rocc.data, resp_OK, tid)
      table(idxAsidTid).flags.input := Bool(false)
    }
  }

  when (readDataPoll) {
    val queue = queueOutput(idxAsidTid)
    genResp(resp_d.bits.rocc.data, resp_NOT_DONE, tid)
    when (queue.deq.fire()) {
      genResp(resp_d.bits.rocc.data, resp_OK, tid, queue.deq.bits)
      table(idxAsidTid).flags.output := Bool(false)
    }
    printfInfo("XF TTable: Saw readDataPoll\n")
  }

  when (unknownCmd) {
    genResp(resp_d.bits.rocc.data, resp_OK, -SInt(err_XFILES_INVALIDTID))
    when (hitAsidTid) {
      genResp(resp_d.bits.rocc.data, resp_OK, tid)
    }
  }

  when (reset) { (0 until numEntries).map(i => { table(i).reset() })}

  //------------------------------------ Printfs, asserts
  val error = Reg(Bool(), init = Bool(false))

  // Check for too many reads without a response
  val readDataPollCount = Reg(UInt(width = 32), init = UInt(0))

  when (newRequest) {
    printfInfo("XF TTable: Saw newRequest\n")
  }

  when (unknownCmd) {
    printfInfo("XF TTable: Saw unknownCmd\n")
  }

  when (writeData) {
    printfInfo("XF TTable: Saw writeData\n")
  }

  when (writeDataLast) {
    printfInfo("XF TTable: Saw writeDataLast\n")
  }

  when (readDataPoll) {
    readDataPollCount := readDataPollCount + UInt(1)
    error := readDataPollCount > UInt(32)
  }

  (0 until numEntries).map(i => {
    // Input Queue
    assert(!(queueInput(i).enq.valid & !queueInput(i).enq.ready),
      "XF TTable: Input queue overflowed\n")
    when (queueInput(i).enq.fire()) {
      printfInfo("XF TTable: queueInput[%d] fired w/ data [0x%x, 0x%x]\n",
        UInt(i), queueInput(i).enq.bits(0), queueInput(i).enq.bits(1)) }

    // Output Queue
    when (queueOutput(i).deq.fire()) {
      printfInfo("XF TTable: queueOutput[%d] fired w/ data 0x%x\n", UInt(i),
      queueOutput(i).deq.bits) }
  })

  when (RegNext(newRequest | writeData | writeDataLast | readDataPoll)) {
    info(table, "xfttable,") }

  // Explicit assertions
  assert (!(resp_d.valid & io.backend.rocc.resp.valid),
    "XF TTable: newRequest resp just aliased backend resp")
  assert(!((writeData | writeDataLast) & !hitAsidTid),
    "XF TTable: writeData or writeDataLast without TTable ASID/TID hit")
  assert (!(error),
    "XF TTable error asserted")
}
