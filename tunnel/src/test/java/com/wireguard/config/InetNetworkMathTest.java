/*
 * Copyright © 2017-2025 WireGuard LLC / GoDeb. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.config;

import org.junit.Assert;
import org.junit.Test;

import java.net.InetAddress;
import java.util.List;

public class InetNetworkMathTest {
    @Test
    public void emptyExcludeLeavesInclude() throws Exception {
        final String out = InetNetworkMath.subtractCsv("0.0.0.0/0, ::/0", "");
        Assert.assertTrue(out.contains("0.0.0.0/0"));
        Assert.assertTrue(out.contains("0:0:0:0:0:0:0:0/0") || out.contains("::/0"));
    }

    @Test
    public void subtractHomeLanFromDefaultRoute() throws Exception {
        final List<InetNetwork> got = InetNetworkMath.subtract(
                List.of(InetNetwork.parse("0.0.0.0/0")),
                List.of(InetNetwork.parse("172.16.1.0/24"))
        );
        Assert.assertFalse(got.isEmpty());
        final InetNetwork excluded = InetNetwork.parse("172.16.1.0/24");
        for (final InetNetwork network : got) {
            Assert.assertFalse(overlaps(network, excluded));
        }
        Assert.assertTrue(contains(got, "8.8.8.8"));
        Assert.assertTrue(contains(got, "10.0.0.1"));
        Assert.assertTrue(contains(got, "172.16.0.1"));
        Assert.assertTrue(contains(got, "172.16.2.1"));
        Assert.assertFalse(contains(got, "172.16.1.50"));
    }

    @Test
    public void subtractCsvExpandsWildcard() throws Exception {
        final String out = InetNetworkMath.subtractCsv("0.0.0.0/0, ::/0", "172.16.1.0/24");
        Assert.assertFalse(out.contains("0.0.0.0/0"));
        Assert.assertTrue(out.contains("0:0:0:0:0:0:0:0/0") || out.contains("::/0"));
    }

    @Test(expected = ParseException.class)
    public void excludeEverythingFails() throws Exception {
        InetNetworkMath.subtractCsv("10.0.0.0/24", "10.0.0.0/24");
    }

    private static boolean contains(final List<InetNetwork> networks, final String ip) throws Exception {
        final InetAddress addr = InetAddress.getByName(ip);
        for (final InetNetwork network : networks) {
            if (coversAddress(network, addr))
                return true;
        }
        return false;
    }

    private static boolean coversAddress(final InetNetwork network, final InetAddress addr) throws Exception {
        // Re-use subtract: if network - {addr/32or128} shrinks, it contained the address.
        final int mask = addr.getAddress().length == 4 ? 32 : 128;
        final InetNetwork single = InetNetwork.parse(addr.getHostAddress() + '/' + mask);
        final List<InetNetwork> after = InetNetworkMath.subtract(List.of(network), List.of(single));
        return after.size() != 1 || !after.get(0).equals(network);
    }

    private static boolean overlaps(final InetNetwork a, final InetNetwork b) throws Exception {
        final List<InetNetwork> left = InetNetworkMath.subtract(List.of(a), List.of(b));
        return left.size() != 1 || !left.get(0).equals(a);
    }
}
