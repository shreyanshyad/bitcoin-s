package org.bitcoins.core.p2p

import org.bitcoins.testkit.core.gen.p2p.ControlMessageGenerator
import org.bitcoins.testkit.util.BitcoinSUnitTest

class AddrMessageTest extends BitcoinSUnitTest {
  it must "have serialization symmetry" in {
    forAll(ControlMessageGenerator.addrMessage) { addr =>
      val fromBytes = AddrMessage.fromBytes(addr.bytes)
      assert(fromBytes == addr)
    }
  }
}
