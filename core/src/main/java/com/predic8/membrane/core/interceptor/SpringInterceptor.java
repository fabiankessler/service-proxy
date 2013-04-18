package com.predic8.membrane.core.interceptor;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import com.predic8.membrane.annot.MCAttribute;
import com.predic8.membrane.annot.MCElement;
import com.predic8.membrane.core.Router;
import com.predic8.membrane.core.exchange.Exchange;

@MCElement(name="interceptor")
public class SpringInterceptor extends AbstractInterceptor implements ApplicationContextAware {

	private String refid;
	private Interceptor i;
	private ApplicationContext ac;
	
	@Required
	@MCAttribute(attributeName="refid")
	public void setRefId(String refid) {
		this.refid = refid;
	}

	public String getRefId() {
		return refid;
	}
	
	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		ac = applicationContext;
		i = (Interceptor) ac.getBean(refid);
	}

	@Override
	public Outcome handleRequest(Exchange exc) throws Exception {
		return i.handleRequest(exc);
	}

	@Override
	public Outcome handleResponse(Exchange exc) throws Exception {
		return i.handleResponse(exc);
	}

	@Override
	public void handleAbort(Exchange exchange) {
		i.handleAbort(exchange);
	}

	@Override
	public String getDisplayName() {
		return i.getDisplayName();
	}

	@Override
	public void setDisplayName(String name) {
		i.setDisplayName(name);
	}

	@Override
	public String getShortDescription() {
		return i.getShortDescription();
	}

	@Override
	public String getLongDescription() {
		return i.getLongDescription();
	}

	@Override
	public String getHelpId() {
		return i.getHelpId();
	}

	@Override
	public void init(Router router) throws Exception {
		super.init(router);
		if (refid != null)
			i = (Interceptor) ac.getBean(refid);
		i.init(router);
	}
	
	public Interceptor getInner() {
		return i;
	}

}