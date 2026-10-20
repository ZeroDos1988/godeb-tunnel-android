/*
 * Copyright © 2017-2025 WireGuard LLC / GoDeb. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.config;

import com.wireguard.util.NonNullForAll;

import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * CIDR set difference: {@code include − exclude}, matching GoDeb's SubtractPrefixes.
 */
@NonNullForAll
public final class InetNetworkMath {
    private InetNetworkMath() {
    }

    public static List<InetNetwork> subtract(final Collection<InetNetwork> include,
                                             final Collection<InetNetwork> exclude) {
        List<InetNetwork> result = new ArrayList<>(include);
        for (final InetNetwork ex : exclude) {
            final List<InetNetwork> next = new ArrayList<>();
            for (final InetNetwork in : result) {
                next.addAll(subtractOne(in, ex));
            }
            result = next;
        }
        return result;
    }

    /**
     * Subtract CSV CIDR lists. Returns a comma-separated AllowedIPs string.
     */
    public static String subtractCsv(final String includeCsv, final String excludeCsv)
            throws ParseException {
        final List<InetNetwork> include = parseList(includeCsv);
        if (include.isEmpty())
            throw new ParseException(InetNetwork.class, includeCsv, "AllowedIPs must not be empty");
        final List<InetNetwork> exclude = parseList(excludeCsv);
        final List<InetNetwork> result = subtract(include, exclude);
        if (result.isEmpty())
            throw new ParseException(InetNetwork.class, excludeCsv, "ExcludedIPs remove all AllowedIPs");
        final Set<String> out = new LinkedHashSet<>();
        for (final InetNetwork network : result)
            out.add(network.toString());
        return Attribute.join(out);
    }

    private static List<InetNetwork> parseList(final String csv) throws ParseException {
        final List<InetNetwork> out = new ArrayList<>();
        for (final String part : Attribute.split(csv)) {
            if (part.isEmpty())
                continue;
            out.add(InetNetwork.parse(part));
        }
        return out;
    }

    private static List<InetNetwork> subtractOne(final InetNetwork base, final InetNetwork remove) {
        if (isV4(base) != isV4(remove))
            return List.of(base);
        if (!overlaps(base, remove))
            return List.of(base);
        if (covers(remove, base))
            return List.of();

        final List<InetNetwork> out = new ArrayList<>();
        final List<InetNetwork> stack = new ArrayList<>();
        stack.add(base);
        while (!stack.isEmpty()) {
            final InetNetwork cur = stack.remove(stack.size() - 1);
            if (!overlaps(cur, remove)) {
                out.add(cur);
                continue;
            }
            if (covers(remove, cur))
                continue;
            final InetNetwork[] halves = split(cur);
            if (halves == null)
                continue;
            stack.add(halves[1]);
            stack.add(halves[0]);
        }
        return out;
    }

    private static boolean isV4(final InetNetwork network) {
        return network.getAddress() instanceof Inet4Address;
    }

    private static boolean overlaps(final InetNetwork a, final InetNetwork b) {
        return containsAddr(a, networkAddress(b)) || containsAddr(b, networkAddress(a));
    }

    private static boolean covers(final InetNetwork outer, final InetNetwork inner) {
        return outer.getMask() <= inner.getMask() && containsAddr(outer, networkAddress(inner));
    }

    private static boolean containsAddr(final InetNetwork network, final InetAddress addr) {
        if (isV4(network) != (addr instanceof Inet4Address))
            return false;
        final BigInteger net = toBigInteger(networkAddress(network));
        final BigInteger host = toBigInteger(addr);
        final int maxBits = isV4(network) ? 32 : 128;
        final int hostBits = maxBits - network.getMask();
        if (hostBits >= maxBits)
            return true;
        final BigInteger mask = BigInteger.ONE.shiftLeft(maxBits).subtract(BigInteger.ONE)
                .shiftRight(hostBits).shiftLeft(hostBits);
        return net.and(mask).equals(host.and(mask));
    }

    private static InetAddress networkAddress(final InetNetwork network) {
        final int maxBits = isV4(network) ? 32 : 128;
        final int hostBits = maxBits - network.getMask();
        final BigInteger addr = toBigInteger(network.getAddress());
        final BigInteger masked = hostBits >= maxBits ? BigInteger.ZERO
                : addr.shiftRight(hostBits).shiftLeft(hostBits);
        return fromBigInteger(masked, isV4(network));
    }

    private static InetNetwork[] split(final InetNetwork network) {
        final int maxBits = isV4(network) ? 32 : 128;
        if (network.getMask() >= maxBits)
            return null;
        final int newMask = network.getMask() + 1;
        final InetAddress leftAddr = networkAddress(network);
        final BigInteger left = toBigInteger(leftAddr);
        final BigInteger bit = BigInteger.ONE.shiftLeft(maxBits - newMask);
        final InetAddress rightAddr = fromBigInteger(left.or(bit), isV4(network));
        try {
            return new InetNetwork[]{
                    InetNetwork.parse(format(leftAddr, newMask)),
                    InetNetwork.parse(format(rightAddr, newMask))
            };
        } catch (final ParseException e) {
            return null;
        }
    }

    private static String format(final InetAddress address, final int mask) {
        return address.getHostAddress().toLowerCase(Locale.ROOT) + '/' + mask;
    }

    private static BigInteger toBigInteger(final InetAddress address) {
        return new BigInteger(1, address.getAddress());
    }

    private static InetAddress fromBigInteger(final BigInteger value, final boolean v4) {
        final int size = v4 ? 4 : 16;
        final byte[] raw = value.toByteArray();
        final byte[] out = new byte[size];
        if (raw.length >= size) {
            System.arraycopy(raw, raw.length - size, out, 0, size);
        } else {
            System.arraycopy(raw, 0, out, size - raw.length, raw.length);
        }
        try {
            return InetAddress.getByAddress(out);
        } catch (final UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
