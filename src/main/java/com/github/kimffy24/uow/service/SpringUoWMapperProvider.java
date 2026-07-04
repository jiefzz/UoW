package com.github.kimffy24.uow.service;

import static pro.jk.ejoker.common.system.enhance.StringUtilx.fmt;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cglib.proxy.Proxy;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StringUtils;

import com.github.kimffy24.uow.annotation.RBind;
import com.github.kimffy24.uow.annotation.UowAggrRef;
import com.github.kimffy24.uow.export.mapper.ILocatorMapper;
import com.github.kimffy24.uow.service.aware.IGenRBinderProvider;

import net.minidev.json.JSONValue;
import pro.jk.ejoker.common.system.enhance.MapUtilx;
import pro.jk.ejoker.common.system.task.io.IOExceptionOnRuntime;

public class SpringUoWMapperProvider implements ApplicationContextAware, ApplicationListener<ApplicationReadyEvent> {
	
	private final static Logger logger = LoggerFactory.getLogger(SpringUoWMapperProvider.class);
	
	private final Map<Class<?>, ILocatorMapper> mapperStore = new ConcurrentHashMap<>();

	private ApplicationContext applicationContext;
	
	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}
	
	/**
	 * 
	 * 在spring 上下文构建完成之后，主动去加载UoW生成的xml文件
	 */
	@Override
    public void onApplicationEvent(ApplicationReadyEvent applicationReadyEvent) {
        ApplicationContext applicationContext = applicationReadyEvent.getApplicationContext();

		loadBindInfoFromUowAggrRef(applicationContext);

        String cp;
		if(null == genRBinderProvider || !StringUtils.hasLength(cp = genRBinderProvider.getPrefixclasspath())) {
			return;
		}
		
		SqlSessionFactory sqlSessionFactory = applicationContext.getBean(SqlSessionFactory.class);
		Configuration configuration = sqlSessionFactory.getConfiguration();
		
		try {
			Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:" + cp + "/*.xml");
			if(null != resources && resources.length > 0) {
				for (Resource configLocation : resources) {
		            XMLMapperBuilder xmlMapperBuilder = new XMLMapperBuilder(
							configLocation.getInputStream(),
							configuration,
							configLocation.toString(),
							configuration.getSqlFragments());
		            xmlMapperBuilder.parse();
		            logger.info("Load uowgen xml: {}", configLocation.getFilename());
		        }
			} else {
		        logger.info("No any uowgen xml was load.");
			}
		} catch (IOException e) {
			throw new IOExceptionOnRuntime(e);
		}

		loadUoWGenBindInfoJson();
	}
	
	/**
	 * 从给定的聚合根类，找出对应的mapper对象<br />
	 * * 从聚合根类找出RBind注解，从注解中找出声明的Mapper类的，再从spring上下文中找出该Mapper类的实例对象。
	 * @param aggrType
	 * @return
	 */
	public ILocatorMapper getAggrMapper(Class<?> aggrType) {
		ILocatorMapper iLocatorMapper = mapperStore.get(aggrType);
		if(null == iLocatorMapper) {
			iLocatorMapper = MapUtilx.getOrAdd(mapperStore, aggrType, this::getAggrMapperInner);
		}
		return iLocatorMapper;
	}
	
	private ILocatorMapper getAggrMapperInner(Class<?> aggrType) {
		Class<? extends ILocatorMapper> mapperType;
		{
			// 手工配置比CodeGen优先
			RBind rBind;
			if(null != (rBind = aggrType.getAnnotation(RBind.class))) {
				mapperType = rBind.value();
			} else {
				String mapperGenType;
				if(StringUtils.hasLength(mapperGenType = uowGenRBindInfo.get(aggrType.getName()))) {
					try {
						Class<?> genMapperType = this.getClass().getClassLoader().loadClass(mapperGenType);
						mapperType = (Class<? extends ILocatorMapper> )genMapperType;
					} catch (ClassNotFoundException e) {
						throw new RuntimeException(fmt("Coundn't found any MapperType for AggregateType[{}] !!! load uow gen Mapper Type faild.", aggrType.getName()), e);
					}
				} else {
					throw new RuntimeException(fmt("Coundn't found any MapperType for AggregateType[{}] !!!", aggrType.getName()));
				}
			}
		}
		
		if(null == mapperType) {
			throw new RuntimeException(fmt("No any MapperType was detected for AggregateType[{}] !!!", aggrType.getName()));
		}
		
		ILocatorMapper bean = applicationContext.getBean(mapperType);
		if(null == bean) {
			throw new RuntimeException(fmt("Coundn't found any instance for MapperType[{}] !!!", mapperType.getName()));
		}
		return bean;
	}
	
	@Qualifier("uow-code-gen-rbind-config-aware")
	@Autowired(required = false)
	private IGenRBinderProvider genRBinderProvider;
	
	private volatile Map<String, String> uowGenRBindInfo = null;
	
	/**
	 * 从Spring上下文中扫描所有ILocatorMapper，通过@UowAggrRef注解直接将聚合类→mapper实例写入mapperStore
	 */
	private void loadBindInfoFromUowAggrRef(ApplicationContext applicationContext) {
		Map<String, ILocatorMapper> mapperBeans = applicationContext.getBeansOfType(ILocatorMapper.class);
		for(Map.Entry<String, ILocatorMapper> entry : mapperBeans.entrySet()) {
			ILocatorMapper value = entry.getValue();

//			Class<?> aClass = value.getClass();

			Class<?> aClass = getMapperInterface(value);

			UowAggrRef uowAggrRef = aClass.getAnnotation(UowAggrRef.class);

			if(null != uowAggrRef) {
				Class<?> aggrType = uowAggrRef.value();
				mapperStore.put(aggrType, value);
				logger.info("Bind aggregate [{}] => mapper [{}] from @UowAggrRef annotation",
						aggrType.getName(),
						aClass.getName());
			}
		}
		logger.info("Loaded {} UoW bind info from @UowAggrRef annotation.", mapperStore.size());
	}


	/**
	 * 从 MyBatis Mapper 代理中获取真实的 Mapper 接口
	 * 兼容各种代理类型（JDK、MyBatis、CGLIB 等）
	 */
	public static Class<?> getMapperInterface(Object mapperProxy) {
		if (mapperProxy == null) {
			throw new IllegalArgumentException("Mapper proxy cannot be null");
		}

		Class<?> clazz = mapperProxy.getClass();

		// 1. 先尝试从实现的接口中获取
		Class<?>[] interfaces = clazz.getInterfaces();
		if (interfaces.length > 0) {
			// 找到第一个不是 JDK 内部接口的（通常是 Mapper 接口）
			for (Class<?> intf : interfaces) {
				String name = intf.getName();
				// 排除 Spring/MyBatis 内部接口
				if (!name.startsWith("org.springframework.")
						&& !name.startsWith("org.apache.ibatis.")
						&& !name.startsWith("com.baomidou.mybatisplus.")) {
					return intf;
				}
			}
			// 如果没有排除任何接口，返回第一个
			return interfaces[0];
		}

		// 2. 尝试从父类获取（CGLIB 代理）
		Class<?> superClass = clazz.getSuperclass();
		if (superClass != null && superClass != Object.class) {
			// 如果父类是 Mapper 接口（不应该发生），检查父类是否实现了什么接口
			Class<?>[] superInterfaces = superClass.getInterfaces();
			if (superInterfaces.length > 0) {
				return superInterfaces[0];
			}
			return superClass;
		}

		// 3. 如果不是代理，直接返回自身
		return clazz;
	}

	private void loadUoWGenBindInfoJson() {
		if(null == uowGenRBindInfo) {
			String genPath;
			if(null == genRBinderProvider
					|| !StringUtils.hasLength(genPath = (
							genRBinderProvider.getPrefixclasspath()
							+ "/"
							+ genRBinderProvider.getJsonConfigFileName()))) {
				logger.warn("No UoW rbind info provider found!");
				uowGenRBindInfo = new HashMap<>();
				return;
			}
			ClassPathResource resource = new ClassPathResource(genPath);
			if(!resource.exists()) {
				logger.warn("Can not load UoW bind info, gen file is not exists! [path: {}]", genPath);
				uowGenRBindInfo = new HashMap<>();
				return;
			}
			try (InputStream inputStream = resource.getInputStream()) {
				Object parse = JSONValue.parse(inputStream);
				if(parse instanceof Map) {
					uowGenRBindInfo = (Map<String, String> )parse;
				} else {
					throw new RuntimeException("uow-gen-rbind-info.json has wrong format !!!");
				}
				logger.info("Load UoW bind info successfully. [path: {}]", genPath);
			} catch (IOException e) {
				throw new IOExceptionOnRuntime(e);
			}
		}
	}
}
