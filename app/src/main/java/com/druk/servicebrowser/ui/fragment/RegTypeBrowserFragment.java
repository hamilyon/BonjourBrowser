/*
 * Copyright (C) 2015 Andriy Druk
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
package com.druk.servicebrowser.ui.fragment;

import android.os.Bundle;
import androidx.fragment.app.Fragment;
import android.util.Log;

import com.druk.servicebrowser.BonjourApplication;
import com.druk.servicebrowser.Config;
import com.druk.servicebrowser.RegTypeManager;
import com.druk.servicebrowser.ui.adapter.ServiceAdapter;
import com.github.druk.rx2dnssd.BonjourService;

import java.util.HashMap;

import io.reactivex.Flowable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;

import static com.druk.servicebrowser.Config.EMPTY_DOMAIN;
import static com.druk.servicebrowser.Config.TCP_REG_TYPE_SUFFIX;
import static com.druk.servicebrowser.Config.UDP_REG_TYPE_SUFFIX;


public class RegTypeBrowserFragment extends ServiceBrowserFragment {

    private static final String TAG = "RegTypeBrowser";

    private final HashMap<String, Disposable> mBrowsers = new HashMap<>();
    private final HashMap<String, BonjourDomain> mServices = new HashMap<>();
    private RegTypeManager mRegTypeManager;

    public static Fragment newInstance(String regType) {
        return fillArguments(new RegTypeBrowserFragment(), EMPTY_DOMAIN, regType);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRegTypeManager = BonjourApplication.getRegTypeManager(getContext());
        mAdapter = new ServiceAdapter(getActivity()) {
            @Override
            public void onBindViewHolder(ViewHolder viewHolder, int i) {
                BonjourDomain domain = (BonjourDomain) getItem(i);
                String regType = domain.getServiceName() + "." + domain.getRegType().split(Config.REG_TYPE_SEPARATOR)[0] + ".";
                String regTypeDescription = mRegTypeManager.getRegTypeDescription(regType);
                if (regTypeDescription != null) {
                    viewHolder.text1.setText(regType + " (" + regTypeDescription + ")");
                } else {
                    viewHolder.text1.setText(regType);
                }
                viewHolder.text2.setText(domain.serviceCount + " services");
                viewHolder.itemView.setOnClickListener(mListener);
                viewHolder.itemView.setBackgroundResource(getBackground(i));
            }
        };
    }

    @Override
    protected void startDiscovery() {
        mDisposable = mRxDnssd.browse(Config.SERVICES_DOMAIN, "local.")
                .subscribeOn(Schedulers.io())
                .subscribe(reqTypeAction, errorAction);
    }

    @Override
    protected void stopDiscovery() {
        super.stopDiscovery();
        mServices.clear();
        synchronized (this) {
            for (Disposable subscription : mBrowsers.values()) {
                subscription.dispose();
            }
            mBrowsers.clear();
        }
    }

    private final Consumer<BonjourService> reqTypeAction = new Consumer<BonjourService>() {
        @Override
        public void accept(BonjourService service) {
            if (service.isLost()) {
                //Ignore this call
                return;
            }
            String[] regTypeParts = service.getRegType().split(Config.REG_TYPE_SEPARATOR);
            String protocolSuffix = regTypeParts[0];
            String serviceDomain = regTypeParts[1];
            if (TCP_REG_TYPE_SUFFIX.equals(protocolSuffix) || UDP_REG_TYPE_SUFFIX.equals(protocolSuffix)) {
                String key = service.getServiceName() + "." + protocolSuffix;
                synchronized (this) {
                    if (!mBrowsers.containsKey(key)) {
                        mBrowsers.put(key, mRxDnssd.browse(key, serviceDomain)
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(RegTypeBrowserFragment.this.servicesAction, RegTypeBrowserFragment.this.errorAction));
                    }
                    mServices.put(createKey(service.getDomain(), service.getRegType(), service.getServiceName()), new BonjourDomain(service));
                }
            } else {
                Log.e("TAG", "Unknown service protocol " + protocolSuffix);
                //Just ignore service with different protocol suffixes
            }
        }
    };

    protected final Consumer<Throwable> errorAction = throwable -> {
        Log.e("DNSSD", "Error: ", throwable);
        RegTypeBrowserFragment.this.showError(throwable);
    };

    private final Consumer<BonjourService> servicesAction = service -> {
        String[] regTypeParts = service.getRegType().split(Config.REG_TYPE_SEPARATOR);
        String serviceRegType = regTypeParts[0];
        String protocolSuffix = regTypeParts[1];
        String key = createKey(EMPTY_DOMAIN, protocolSuffix + "." + service.getDomain(), serviceRegType);
        BonjourDomain domain = mServices.get(key);
        if (domain != null) {
            if (service.isLost()) {
                domain.serviceCount--;
            } else {
                domain.serviceCount++;
            }
            final int itemsCount = mAdapter.getItemCount();
            mAdapter.clear();
            Flowable.fromIterable(mServices.values())
                    .filter(bonjourDomain -> bonjourDomain.serviceCount > 0)
                    .subscribe(service1 -> mAdapter.add(service1), throwable -> {/* empty */}, () -> {
                        RegTypeBrowserFragment.this.showList(itemsCount);
                        mAdapter.notifyDataSetChanged();
                    });
        } else {
            Log.w(TAG, "Service from unknown service type " + key);
        }
    };

    public static String createKey(String domain, String regType, String serviceName) {
        return domain + regType + serviceName;
    }

    public static class BonjourDomain extends BonjourService {
        int serviceCount = 0;

        BonjourDomain(BonjourService bonjourService){
            super(new BonjourService.Builder(bonjourService));
        }
    }
}
