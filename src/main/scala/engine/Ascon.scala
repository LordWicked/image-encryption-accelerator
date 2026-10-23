package engine

import chisel3._
import chisel3.util._

import AsconCons._

object AsconState extends ChiselEnum {
  val sIdle, sInit, sWaitPixel, sPermute, sPad, sFinal, sTag = Value
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

  // S-box
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

class AsconRoundUnit extends Module {
  val io = IO(new RoundIO)
  io.stateReg_out := AsconPermutation.compute(
    io.stateReg_in,
    io.perm_counter_in
  )
}

class Ascon(numBlocks: Int = 1024) extends Module {

  // 1 Phase
  val io = IO(new AsconIO())

  // Initialize the state machine
  val state = RegInit(sIdle)
  // stateReg = [x0,x1,x2,x3,x4] in which x0 and x1 corresponds to the data
  val stateReg = RegInit(VecInit(Seq.fill(5)(0.U(64.W))))

  val perm_counter = RegInit(0.U(4.W))

  val keyReg = Reg(UInt(128.W))

  val secondHalf = RegInit(false.B)

  val blockCount = RegInit(0.U(log2Ceil(numBlocks).W))
  val lastBlock = RegInit(false.B)

  // Stantard/Safety values
  io.pixels_in.ready := false.B
  io.enc_pixels_out.valid := false.B
  io.enc_pixels_out.bits := 0.U

  // TODO Mention missed deletion of duplicates in Scrum story

  io.dbg_state := stateReg

  val round = Module(new AsconRoundUnit)
  round.io.perm_counter_in := perm_counter
  round.io.stateReg_in := stateReg
  val next = round.io.stateReg_out

  // Phase n1
  switch(state) {
    is(sIdle) {
      when(io.start) {
        // Initialization Vector decided by the Ascon Creators
        // stateReg(0) := "h80400c0600000000".U(64.W)   // Ascon-128's IV
        stateReg(0) := "h00001000808C0001".U(64.W) // Ascon-AEAD128

        keyReg := io.key

        stateReg(1) := io.key(127, 64)
        stateReg(2) := io.key(63, 0)

        stateReg(3) := io.nonce(127, 64)
        stateReg(4) := io.nonce(63, 0)
        state := sInit
        perm_counter := 0.U
        blockCount := 0.U
        lastBlock := false.B

      }
    }

    is(sInit) {
      stateReg := next
      perm_counter := perm_counter + 1.U
      when(perm_counter === 11.U) {
        stateReg(3) := next(3) ^ keyReg(127, 64)
        stateReg(4) := next(4) ^ keyReg(63, 0) ^ AsconCons.DOMAIN_SEP
        state := sWaitPixel
      }
    }

    is(sWaitPixel) {
      io.pixels_in.ready := io.enc_pixels_out.ready
      io.enc_pixels_out.valid := io.pixels_in.valid

      val rateWord = Mux(secondHalf, stateReg(1), stateReg(0))
      val cipher = rateWord ^ io.pixels_in.bits
      io.enc_pixels_out.bits := cipher

      when(io.pixels_in.fire) {
        when(!secondHalf) {
          stateReg(0) := cipher
        }.otherwise {
          stateReg(1) := cipher
          perm_counter := 4.U
          state := sPermute
          blockCount := blockCount + 1.U
          lastBlock := blockCount === (numBlocks - 1).U
        }
        secondHalf := !secondHalf
      }
    }

    is(sPermute) {
      stateReg := next
      perm_counter := perm_counter + 1.U
      when(perm_counter === 11.U) {
        when(lastBlock) {
          state := sPad
        }.otherwise {
          state := sWaitPixel
        }
      }
    }

    is(sPad) {
      stateReg(0) := stateReg(0) ^ 1.U
      stateReg(2) := stateReg(2) ^ keyReg(127, 64)
      stateReg(3) := stateReg(3) ^ keyReg(63, 0)
      perm_counter := 0.U
      state := sFinal
    }

    is(sFinal) {
      stateReg := next
      perm_counter := perm_counter + 1.U
      when(perm_counter === 11.U) {
        stateReg(3) := next(3) ^ keyReg(127, 64) // tag = x3||x4 ^ key
        stateReg(4) := next(4) ^ keyReg(63, 0)
        state := sTag
      }

    }

    is(sTag) {
      io.enc_pixels_out.valid := true.B
      io.enc_pixels_out.bits := Mux(secondHalf, stateReg(4), stateReg(3))
      when(io.enc_pixels_out.fire) {
        secondHalf := !secondHalf
        when(secondHalf) { state := sIdle }
      }
    }

  }

}

class RoundIO() extends Bundle {
  val stateReg_in = Input(Vec(5, UInt(64.W)))
  val perm_counter_in = Input(UInt(4.W))

  val stateReg_out = Output(Vec(5, UInt(64.W)))
}

// Simplest IO possible for MVP. Sends each time 64-bit transfers, 8 grayscale pixels each, two transfers per 128-bit block”.
// So it has only 64 clock which is extremely fast.
// Without sending bytes in a middle of a pixel problems.
class AsconIO() extends Bundle {
  val start = Input(Bool())
  val key = Input(UInt(128.W))
  val nonce = Input(UInt(128.W))

  // Debug State Output TODO keep as generator parameter DEBUG?
  val dbg_state = Output(Vec(5, UInt(64.W)))

  // 8 pixels
  val pixels_in = (Flipped(Decoupled(UInt(64.W))))
  val enc_pixels_out = Decoupled(UInt(64.W))
}
