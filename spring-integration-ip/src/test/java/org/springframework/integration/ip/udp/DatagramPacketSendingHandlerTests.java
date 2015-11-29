/*
 * Copyright 2002-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.ip.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.LogFactory;
import org.junit.Test;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.integration.ip.IpHeaders;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;

/**
 * @author Mark Fisher
 * @author Gary Russell
 * @since 2.0
 */
public class DatagramPacketSendingHandlerTests {

	private boolean noMulticast;

	@Test
	public void verifySend() throws Exception {
		byte[] buffer = new byte[8];
		final DatagramPacket receivedPacket = new DatagramPacket(buffer, buffer.length);
		final CountDownLatch received = new CountDownLatch(1);
		final AtomicInteger testPort = new AtomicInteger();
		final CountDownLatch listening = new CountDownLatch(1);
		Executors.newSingleThreadExecutor().execute(new Runnable() {
			@Override
			public void run() {
				try {
					DatagramSocket socket = new DatagramSocket();
					testPort.set(socket.getLocalPort());
					listening.countDown();
					socket.receive(receivedPacket);
					received.countDown();
					socket.close();
				}
				catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
		assertTrue(listening.await(10, TimeUnit.SECONDS));
		UnicastSendingMessageHandler handler =
				new UnicastSendingMessageHandler("localhost",
						testPort.get(),
						UnicastDatagramSocketRegistry.INSTANCE);
		String payload = "foo";
		handler.handleMessage(MessageBuilder.withPayload(payload).build());
		assertTrue(received.await(3000, TimeUnit.MILLISECONDS));
		byte[] src = receivedPacket.getData();
		int length = receivedPacket.getLength();
		int offset = receivedPacket.getOffset();
		byte[] dest = new byte[length];
		System.arraycopy(src, offset, dest, 0, length);
		assertEquals(payload, new String(dest));
		handler.stop();
	}

	@Test
	public void verifySendWithAck() throws Exception {

		final AtomicInteger testPort = new AtomicInteger();
		final AtomicInteger ackPort = new AtomicInteger();

		byte[] buffer = new byte[1000];
		final DatagramPacket receivedPacket = new DatagramPacket(buffer, buffer.length);
		final CountDownLatch listening = new CountDownLatch(1);
		final CountDownLatch ackListening = new CountDownLatch(1);
		final CountDownLatch ackSent = new CountDownLatch(1);
		Executors.newSingleThreadExecutor().execute(new Runnable() {
			@Override
			public void run() {
				try {
					DatagramSocket socket = new DatagramSocket();
					testPort.set(socket.getLocalPort());
					listening.countDown();
					assertTrue(ackListening.await(10, TimeUnit.SECONDS));
					socket.receive(receivedPacket);
					socket.close();
					DatagramPacketMessageMapper mapper = new DatagramPacketMessageMapper();
					mapper.setAcknowledge(true);
					mapper.setLengthCheck(true);
					Message<byte[]> message = mapper.toMessage(new DatagramPacketWrapper(receivedPacket, null));
					Object id = message.getHeaders().get(IpHeaders.ACK_ID);
					byte[] ack = id.toString().getBytes();
					DatagramPacket ackPack = new DatagramPacket(ack, ack.length,
							                        new InetSocketAddress("localHost", ackPort.get()));
					DatagramSocket out = new DatagramSocket();
					out.send(ackPack);
					out.close();
					ackSent.countDown();
				}
				catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
		listening.await(10000, TimeUnit.MILLISECONDS);
		UnicastSendingMessageHandler handler =
				new UnicastSendingMessageHandler("localhost",
						testPort.get(),
						UnicastDatagramSocketRegistry.INSTANCE,
						true,
						true,
						"localhost",
						0,
						5000);
		handler.setBeanFactory(mock(BeanFactory.class));
		handler.afterPropertiesSet();
		handler.start();
		waitAckListening(handler);
		ackPort.set(handler.getAckPort());
		ackListening.countDown();
		String payload = "foobar";
		handler.handleMessage(MessageBuilder.withPayload(payload).build());
		assertTrue(ackSent.await(10000, TimeUnit.MILLISECONDS));
		byte[] src = receivedPacket.getData();
		int length = receivedPacket.getLength();
		int offset = receivedPacket.getOffset();
		byte[] dest = new byte[6];
		System.arraycopy(src, offset+length-6, dest, 0, 6);
		assertEquals(payload, new String(dest));
		handler.stop();
	}

	public void waitAckListening(UnicastSendingMessageHandler handler) throws InterruptedException {
		int n = 0;
		while (n++ < 100 && handler.getAckPort() == 0) {
			Thread.sleep(100);
		}
		assertTrue(n < 100);
	}

	@Test
	public void verifySendMulticast() throws Exception {
		MulticastSocket socket;
		try {
			socket = new MulticastSocket();
		}
		catch (Exception e) {
			return;
		}
		final int testPort = socket.getLocalPort();
		final String multicastAddress = "225.6.7.8";
		final String payload = "foo";
		final CountDownLatch listening = new CountDownLatch(2);
		final CountDownLatch received = new CountDownLatch(2);
		Runnable catcher = new Runnable() {
			@Override
			public void run() {
				try {
					byte[] buffer = new byte[8];
					DatagramPacket receivedPacket = new DatagramPacket(buffer, buffer.length);
					MulticastSocket socket = new MulticastSocket(testPort);
					InetAddress group = InetAddress.getByName(multicastAddress);
					socket.joinGroup(group);
					listening.countDown();
					LogFactory.getLog(getClass())
						.debug(Thread.currentThread().getName() + " waiting for packet");
					socket.receive(receivedPacket);
					socket.close();
					byte[] src = receivedPacket.getData();
					int length = receivedPacket.getLength();
					int offset = receivedPacket.getOffset();
					byte[] dest = new byte[length];
					System.arraycopy(src, offset, dest, 0, length);
					assertEquals(payload, new String(dest));
					LogFactory.getLog(getClass())
						.debug(Thread.currentThread().getName() + " received packet");
					received.countDown();
				}
				catch (Exception e) {
					noMulticast = true;
					listening.countDown();
					e.printStackTrace();
				}
			}
		};
		Executor executor = Executors.newFixedThreadPool(2);
		executor.execute(catcher);
		executor.execute(catcher);
		listening.await(10000, TimeUnit.MILLISECONDS);
		if (noMulticast) {
			socket.close();
			return;
		}
		MulticastSendingMessageHandler handler = new MulticastSendingMessageHandler(multicastAddress, testPort);
		handler.handleMessage(MessageBuilder.withPayload(payload).build());
		assertTrue(received.await(10000, TimeUnit.MILLISECONDS));
		handler.stop();
		socket.close();
	}

	@Test
	public void verifySendMulticastWithAcks() throws Exception {

		MulticastSocket socket;
		try {
			socket = new MulticastSocket();
		}
		catch (Exception e) {
			return;
		}
		final int testPort = socket.getLocalPort();
		final AtomicInteger ackPort = new AtomicInteger();

		final String multicastAddress = "225.6.7.8";
		final String payload = "foobar";
		final CountDownLatch listening = new CountDownLatch(2);
		final CountDownLatch ackListening = new CountDownLatch(1);
		final CountDownLatch ackSent = new CountDownLatch(2);
		Runnable catcher = new Runnable() {
			@Override
			public void run() {
				try {
					byte[] buffer = new byte[1000];
					DatagramPacket receivedPacket = new DatagramPacket(buffer, buffer.length);
					MulticastSocket socket = new MulticastSocket(testPort);
					socket.setSoTimeout(8000);
					InetAddress group = InetAddress.getByName(multicastAddress);
					socket.joinGroup(group);
					listening.countDown();
					assertTrue(ackListening.await(10, TimeUnit.SECONDS));
					LogFactory.getLog(getClass()).debug(Thread.currentThread().getName() + " waiting for packet");
					socket.receive(receivedPacket);
					socket.close();
					byte[] src = receivedPacket.getData();
					int length = receivedPacket.getLength();
					int offset = receivedPacket.getOffset();
					byte[] dest = new byte[6];
					System.arraycopy(src, offset+length-6, dest, 0, 6);
					assertEquals(payload, new String(dest));
					LogFactory.getLog(getClass()).debug(Thread.currentThread().getName() + " received packet");
					DatagramPacketMessageMapper mapper = new DatagramPacketMessageMapper();
					mapper.setAcknowledge(true);
					mapper.setLengthCheck(true);
					Message<byte[]> message = mapper.toMessage(new DatagramPacketWrapper(receivedPacket, null));
					Object id = message.getHeaders().get(IpHeaders.ACK_ID);
					byte[] ack = id.toString().getBytes();
					DatagramPacket ackPack = new DatagramPacket(ack, ack.length,
												new InetSocketAddress("localHost", ackPort.get()));
					DatagramSocket out = new DatagramSocket();
					out.send(ackPack);
					out.close();
					ackSent.countDown();
					socket.close();
				}
				catch (Exception e) {
					noMulticast = true;
					listening.countDown();
					e.printStackTrace();
				}
			}
		};
		Executor executor = Executors.newFixedThreadPool(2);
		executor.execute(catcher);
		executor.execute(catcher);
		listening.await(10000, TimeUnit.MILLISECONDS);
		if (this.noMulticast) {
			socket.close();
			return;
		}
		MulticastSendingMessageHandler handler =
			new MulticastSendingMessageHandler(multicastAddress, testPort, true, true, "localhost", 0, 10000);
		handler.setMinAcksForSuccess(2);
		handler.afterPropertiesSet();
		handler.start();
		waitAckListening(handler);
		ackPort.set(handler.getAckPort());
		ackListening.countDown();
		handler.handleMessage(MessageBuilder.withPayload(payload).build());
		assertTrue(ackSent.await(10000, TimeUnit.MILLISECONDS));
		handler.stop();
		socket.close();
	}

}
