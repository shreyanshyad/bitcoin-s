package org.scalacoin.script.parsing

import org.scalacoin.script.constant.{ScriptNumberImpl, OP_14}
import org.scalacoin.script.crypto.OP_HASH160
import org.scalacoin.script.stack.OP_DUP
import org.scalacoin.util.{ScalacoinUtil, TestUtil}
import org.scalatest.{FlatSpec, MustMatchers}

/**
 * Created by chris on 1/7/16.
 */
class ScriptParserTest extends FlatSpec with MustMatchers with ScriptParser with ScalacoinUtil {


  "ScriptParser" must "parse an input script" in {
    val parsedInput = parse(TestUtil.p2pkhInputScriptNotParsedAsm)
    parsedInput must be (TestUtil.p2pkhInputScriptAsm)
  }

  it must "parse a pay-to-pubkey-hash output script" in {
    val parsedOutput = parse(TestUtil.p2pkhOutputScriptNotParsedAsm)
    parsedOutput must be (TestUtil.p2pkhOutputScriptAsm)
  }

  it must "parse a pay-to-script-hash output script" in {
    val parsedOutput = parse(TestUtil.p2shOutputScriptNotParsedAsm)
    parsedOutput must be (TestUtil.p2shOutputScriptAsm)
  }

  it must "parse a pay-to-script-hash input script" in {
    val parsedInput = parse(TestUtil.p2shInputScriptNotParsedAsm)
    parsedInput must be (TestUtil.p2shInputScriptAsm)
  }

  it must "parse a p2pkh input script from a byte array to script tokens" in {
    /*val byteArray = TestUtil.p2pkhInputScript.getBytes.toList*/
    val byteArray : List[Byte] = decodeHex("76a914")
    parse(byteArray) must be (List(OP_DUP, OP_HASH160, ScriptNumberImpl(20)))
  }

}
