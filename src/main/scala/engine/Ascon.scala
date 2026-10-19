package engine

import chisel3._
import chisel3.util._

import AsconCons._

object AsconState extends ChiselEnum {
  val sIdle, sInit, sWaitPixel, sPermute = Value
}

object AsconPermutation {

  def rot_right(x: UInt, n: Int): UInt = {
    Cat(x(n - 1, 0), x(63, n))
  }

  def constantAddition(round_number: UInt, state_reg: Vec[UInt]): Vec[UInt] = {
    VecInit(
      state_reg(0),
      state_reg(1),
      state_reg(2) ^ AsconCons.const_addition(round_number),
      state_reg(3),
      state_reg(4)
    )
  }

  def substitutionLayer(cur_state: Vec[UInt]): Vec[UInt] = {
    val x0 = cur_state(0)
    val x1 = cur_state(1)
    val x2 = cur_state(2)
    val x3 = cur_state(3)
    val x4 = cur_state(4)

    val in0 = x0 ^ x4
    val in4 = x4 ^ x3
    val in2 = x2 ^ x1
    val in1 = x1
    val in3 = x3

    val t0 = (~in0) & in1
    val t1 = (~in1) & in2
    val t2 = (~in2) & in3
    val t3 = (~in3) & in4
    val t4 = (~in4) & in0

    val out0 = (in0 ^ t1) ^ (in4 ^ t0)
    val out1 = (in1 ^ t2) ^ (in0 ^ t1)
    val out2 = ~(in2 ^ t3)
    val out3 = (in3 ^ t4) ^ (in2 ^ t3)
    val out4 = in4 ^ t0

    VecInit(out0, out1, out2, out3, out4)
  }

  def linearDiffusionLayer(cur_state: Vec[UInt]): Vec[UInt] = {
    val x0 = cur_state(0)
    val x1 = cur_state(1)
    val x2 = cur_state(2)
    val x3 = cur_state(3)
    val x4 = cur_state(4)

    val y0 = x0 ^ rot_right(x0, 19) ^ rot_right(x0, 28)
    val y1 = x1 ^ rot_right(x1, 61) ^ rot_right(x1, 39)
    val y2 = x2 ^ rot_right(x2, 1) ^ rot_right(x2, 6)
    val y3 = x3 ^ rot_right(x3, 10) ^ rot_right(x3, 17)
    val y4 = x4 ^ rot_right(x4, 7) ^ rot_right(x4, 41)

    VecInit(y0, y1, y2, y3, y4)
  }

  def compute(s: Vec[UInt], rc: UInt): Vec[UInt] = {
    val sConst = constantAddition(rc, s)
    val sBox = substitutionLayer(sConst)
    linearDiffusionLayer(sBox)
  }
}

import AsconState._
class Ascon() extends Module {

  // 1 Phase
  val io = IO(new AsconIO())

  // Initialize the state machine
  val state = RegInit(sIdle)
  // addr = [x0,x1,x2,x3,x4] in which x0 corresponds to the data
  val addr = RegInit(VecInit(Seq.fill(5)(0.U(64.W))))

  val perm_counter = RegInit(0.U(4.W))

  // Stantard/Safety values
  io.pixels_in.ready := false.B
  io.enc_pixels_out.valid := false.B
  io.enc_pixels_out.bits := 0.U

  // NOTE: This only works for 64 bits
  def rot_right(x: UInt, n: Int) = {
    Cat(x(n - 1, 0), x(63, 0))
  }

  def constantAddition(round_number: UInt, state_reg: Vec[UInt]): Vec[UInt] = {
    // An array that has pre-defined values to map the round to a specific

    VecInit(
      state_reg(0),
      state_reg(1),
      state_reg(2) ^ AsconCons.const_addition(round_number),
      state_reg(3),
      state_reg(4)
    )
  }

  def substitutionLater(cur_state: Vec[UInt]): Vec[UInt] = {
    val x0 = cur_state(0)
    val x1 = cur_state(1)
    val x2 = cur_state(2)
    val x3 = cur_state(3)
    val x4 = cur_state(4)

    val in0 = x0 ^ x4
    val in4 = x4 ^ x3
    val in2 = x2 ^ x1
    val in1 = x1
    val in3 = x3

    val t0 = (~in0) & in1
    val t1 = (~in1) & in2
    val t2 = (~in2) & in3
    val t3 = (~in3) & in4
    val t4 = (~in4) & in0

    val out0 = (in0 ^ t1) ^ (in4 ^ t0)
    val out1 = (in1 ^ t2) ^ (in0 ^ t1)
    val out2 = ~(in2 ^ t3)
    val out3 = (in3 ^ t4) ^ (in2 ^ t3)
    val out4 = in4 ^ t0

    VecInit(out0, out1, out2, out3, out4)
  }

  def linearDiffusionLayer(cur_state: Vec[UInt]): Vec[UInt] = {
    val x0 = cur_state(0)
    val x1 = cur_state(1)
    val x2 = cur_state(2)
    val x3 = cur_state(3)
    val x4 = cur_state(4)

    val y0 = x0 ^ rot_right(x0, 19) ^ rot_right(x0, 28)
    val y1 = x1 ^ rot_right(x1, 61) ^ rot_right(x1, 39)
    val y2 = x2 ^ rot_right(x2, 1) ^ rot_right(x2, 6)
    val y3 = x3 ^ rot_right(x3, 10) ^ rot_right(x3, 17)
    val y4 = x4 ^ rot_right(x4, 7) ^ rot_right(x4, 41)

    VecInit(y0, y1, y2, y3, y4)
  }

  // Phase n1
  switch(state) {
    is(sIdle) {
      when(io.start) {
        // Initialization Vector decided by the Ascon Creators
        addr(0) := "h80400c0600000000".U(64.W)

        addr(1) := io.key(127, 64)
        addr(2) := io.key(63, 0)

        addr(3) := io.nounce(127, 64)
        addr(4) := io.nounce(63, 0)
        state := sInit
        perm_counter := 0.U
      }
    }
    // NOT FINISHED -> Assigned to colleague Max
    is(sInit) {}

  }

}

// Simplest IO possible for MVP. Sends each time 64 pixels (grayscale), 64 times.
//So it has only 64 clock which is extremely fast.
//Without sending bytes in a middle of a pixel problems.
class AsconIO() extends Bundle {
  val start = Input(Bool())
  val key = Input(UInt(128.W))
  val nounce = Input(UInt(128.W))

  // 64 pixels
  val pixels_in = (Flipped(Decoupled(UInt(64.W))))
  val enc_pixels_out = Decoupled(UInt(64.W))
}
