/**
 * Dianping.com Inc.
 * Copyright (c) 2003-2013 All Rights Reserved.
 */
package com.dianping.pigeon.remoting.invoker.route.balance;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import com.dianping.pigeon.remoting.invoker.ClientManager;
import com.dianping.pigeon.remoting.invoker.route.quality.RequestQualityManager;
import org.apache.commons.lang.StringUtils;
import com.dianping.pigeon.log.Logger;

import com.dianping.pigeon.config.ConfigManager;
import com.dianping.pigeon.config.ConfigManagerLoader;
import com.dianping.pigeon.log.LoggerLoader;
import com.dianping.pigeon.remoting.common.domain.InvocationRequest;
import com.dianping.pigeon.remoting.invoker.Client;
import com.dianping.pigeon.remoting.invoker.config.InvokerConfig;
import com.dianping.pigeon.remoting.invoker.exception.ServiceUnavailableException;
import com.dianping.pigeon.remoting.invoker.route.statistics.ServiceStatisticsHolder;
import com.dianping.pigeon.remoting.invoker.util.InvokerHelper;

public abstract class AbstractLoadBalance implements LoadBalance {

	private static final Logger logger = LoggerLoader.getLogger(AbstractLoadBalance.class);

	protected Random random = new Random();
	
	private static final ConfigManager configManager = ConfigManagerLoader.getConfigManager();

	@Override
	public Client select(List<Client> clients, InvokerConfig<?> invokerConfig, InvocationRequest request) {
		Client selectedClient = null;
		String forceAddress = InvokerHelper.getAddress();
		if (StringUtils.isNotBlank(forceAddress)) {
			// 客户端强制路由
			if (forceAddress.startsWith("localhost") || forceAddress.startsWith("127.0.0.1")) {
				if (forceAddress.lastIndexOf(":") != -1) {
					forceAddress = configManager.getLocalIp() + forceAddress.substring(forceAddress.lastIndexOf(":"));
				}
			}

			// 拿到未经region或其他策略过滤的client列表
			List<Client> allClients = ClientManager.getInstance().getClusterListener().getClientList(invokerConfig);
			for (Client client : allClients) {
				if (forceAddress.equals(client.getAddress())) {
					selectedClient = client;
					break;
				}
			}
			if (selectedClient == null) {
				throw new ServiceUnavailableException("address[" + forceAddress
						+ "] is not in available providers of service:" + request.getServiceName()
						+ ", available providers:" + allClients);
			}
		} else {
			if (clients == null || clients.isEmpty()) {
				return null;
			}
			try {
				selectedClient = doSelect(clients, invokerConfig, request,
						getWeights(clients, request));
			} catch (Throwable e) {
				logger.error("failed to do load balance[" + getClass().getName() + "], detail: " + e.getMessage()
						+ ", use random instead.", e);
				selectedClient = clients.get(random.nextInt(clients.size()));
			}
		}
		if (logger.isDebugEnabled()) {
			if (ServiceStatisticsHolder.checkRequestNeedStat(request)) {
				logger.debug("total requests to " + selectedClient.getAddress() + " in last second:"
						+ ServiceStatisticsHolder.getCapacityBucket(selectedClient.getAddress()).getLastSecondRequest());
			}
		}
		return selectedClient;
	}

	/**
	 * [w1, w2, w3, maxWeightIndex]
	 *
	 * @param clients
	 * @param request
     * @return
     */
	private int[] getWeights(List<Client> clients, InvocationRequest request) {
		int clientSize = clients.size();
		int[] weights = new int[clientSize + 1];
		int maxWeightIdx = 0;
		int maxWeight = Integer.MIN_VALUE;
		for (int i = 0; i < clientSize; i++) {
			int effectiveWeight = LoadBalanceManager.getEffectiveWeight(clients.get(i).getAddress());
			weights[i] = RequestQualityManager.INSTANCE.adjustWeightWithQuality(effectiveWeight,
					clients.get(i).getAddress(), request);
			if (weights[i] > maxWeight) {
				maxWeight = weights[i];
				maxWeightIdx = i;
			}
		}
		weights[clientSize] = maxWeightIdx;
		if (logger.isDebugEnabled()) {
			logger.debug("effective weights: " + Arrays.toString(weights));
		}
		return weights;
	}

	protected abstract Client doSelect(List<Client> clients, InvokerConfig<?> invokerConfig, InvocationRequest request,
			int[] weights);

}
