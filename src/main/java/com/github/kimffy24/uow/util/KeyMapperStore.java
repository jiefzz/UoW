package com.github.kimffy24.uow.util;

import static pro.jk.ejoker.common.system.enhance.StringUtilx.fmt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.regex.Pattern;

import com.github.kimffy24.uow.annotation.MappingColumn;
import com.github.kimffy24.uow.annotation.MappingComment;
import com.github.kimffy24.uow.annotation.MappingType;

import pro.jk.ejoker.common.context.annotation.persistent.PersistentIgnore;
import pro.jk.ejoker.common.system.enhance.MapUtilx;
import pro.jk.ejoker.common.system.enhance.StringUtilx;
import pro.jk.ejoker.common.utils.genericity.GenericDefinedType;
import pro.jk.ejoker.common.utils.genericity.GenericExpression;
import pro.jk.ejoker.common.utils.genericity.GenericExpressionFactory;

public final class KeyMapperStore {

	private final static KMStore instance = new KMStore();
	
	private final static AtomicBoolean useSnack = new AtomicBoolean(true);
    
	// 第一步：分离连续大写（缩写词）与紧接的首字母大写单词
	//   e.g. ICBCBrand → ICBC_Brand,  URLPath → URL_Path
	private static Pattern acronymBoundary = Pattern.compile("([A-Z]+)([A-Z][a-z]+)");
	// 第二步：分离小写单词与紧接的大写（含缩写词）
	//   e.g. baseAccount → base_Account,  userID → user_ID
	private static Pattern lowerUpperBoundary = Pattern.compile("([a-z]+)([A-Z])");
	
	public static KMStore getIntance() {
		return instance;
	}
	
	public static void trunToSnack() {
		useSnack.set(true);
	}
	
	public static void trunToCamel() {
		useSnack.set(false);
	}

	public static String humpToLine2(String str) {
		// 先分离缩写词与后续首字母大写单词（连续大写作为整体保留）
		str = acronymBoundary.matcher(str).replaceAll("$1_$2");
		// 再分离小写与后续大写（含缩写词开头的大写字母）
		str = lowerUpperBoundary.matcher(str).replaceAll("$1_$2");
		return str.toLowerCase();
	}
	
	public final static class KMStore {
		
		private final Map<String, List<Item>> resultCaches = new ConcurrentHashMap<>();
		
		public List<Item> getAnaResult(Class<?> t) {
			return MapUtilx.getOrAdd(resultCaches, t.getName(), () -> {

				List<GenericExpression> path = new ArrayList<>();
				{
					GenericExpression genericExpress = GenericExpressionFactory.getGenericExpress(t);
					path.add(genericExpress);
					while(null != (genericExpress = genericExpress.getParent())) {
						path.add(genericExpress);
					}
				}
				
				Set<String> ignores = new HashSet<>();
				Set<String> processedKeys = new HashSet<>();

				List<List<Item>> groupColumns = new ArrayList<>();
				for(GenericExpression ge : path) {
					List<Item> subColumns = new ArrayList<>();
					groupColumns.add(subColumns);
					ge.forEachFieldExpressions((name, gdf) -> {
						if(gdf.field.isAnnotationPresent(PersistentIgnore.class) || ignores.contains(name)) {
							ignores.add(name);
							return;
						}
						processedKeys.add(name);

						GenericDefinedType gdt = gdf.genericDefinedType;
						if(gdt.isArray || gdt.isWildcardType || !gdt.allHasMaterialized)
							throw new RuntimeException();

						Class<?> type = gdt.rawClazz;
						String jdbcType;
						
						MappingType mappingType = gdf.field.getAnnotation(MappingType.class);
						MappingComment mappingCommentAnno = gdf.field.getAnnotation(MappingComment.class);
						MappingColumn annotation = gdf.field.getAnnotation(MappingColumn.class);
						
						if(null != mappingType && StringUtilx.isSenseful(mappingType.jdbcType())) {
							jdbcType = mappingType.jdbcType();
						} else {
							if(String.class.equals(type)) {
								jdbcType = "VARCHAR";
							} else if(Short.class.equals(type) || short.class.equals(type)) {
								jdbcType = "TINYINT";
							} else if(Integer.class.equals(type) || int.class.equals(type)) {
								jdbcType = "INTEGER";
							} else if(Double.class.equals(type) || double.class.equals(type) || Float.class.equals(type) || float.class.equals(type)) {
								jdbcType = "DOUBLE";
							} else if(Date.class.equals(type)) {
								jdbcType = "TIMESTAMP";
							} else if(Boolean.class.equals(type) || boolean.class.equals(type)) {
								jdbcType = "BOOLEAN";
							} else if(Long.class.equals(type) || long.class.equals(type)) {
								jdbcType = "BIGINT";
							} else {
								jdbcType = "{NeedYourCode}";
							}
						}
						
						String tableColumnComment = "";
						if(null != mappingCommentAnno && StringUtilx.isSenseful(mappingCommentAnno.value())) {
							tableColumnComment = mappingCommentAnno.value();
						}
						
						TableItem ti;
						if(null != mappingType) {
							String tableType = mappingType.tableType();
							if(!StringUtilx.isSenseful(tableType)) {
								tableType = jdbcType;
							}
							String attr = mappingType.tableAttr();
							ti = TableItem.of(tableType, attr, tableColumnComment);
						} else {
							ti = TableItem.of(jdbcType, "DEFAULT NULL", tableColumnComment);
						}
						
						Item item;
						
						if(null == annotation) {
							subColumns.add(item = Item.of(name, jdbcType, useSnack.get()));
						} else {
							String column = annotation.value();
							if(!StringUtilx.isSenseful(column))
								throw new RuntimeException(fmt(
										"Mapping column name is null or empty!!![field: {}, source: {}]",
										name,
										ge.expressSignature));
							subColumns.add(item = Item.of(name, jdbcType, column));
						}

						item.tableItem = ti;
					});
				}
				Collections.reverse(groupColumns);
				List<Item> columns = new ArrayList<>();
				groupColumns.forEach(sl -> sl.forEach(columns::add));
				
				return new ArrayList<>(columns);
			});
		}
	}

    public final static class Item {
    	public String key;
    	public String jdbcType;
    	public String sqlClName;
    	
    	public TableItem tableItem = null;

		public final static Item of(String key, String jdbcType, boolean toSnack) {
			Item item = new Item();
			item.key = key;
			item.jdbcType = jdbcType;
			item.sqlClName = toSnack ? humpToLine2(key) : key;
			return item;
		}

		public final static Item of(String key, String jdbcType, String mappingColumnName) {
			Item item = new Item();
			item.key = key;
			item.jdbcType = jdbcType;
			item.sqlClName = mappingColumnName;
			return item;
		}


		public final String buildFilter(String factor) {
			return fmt("`{}` {} {}",
					this.sqlClName,
					factor,
					this.buildSep());
		}

		public final String buildSep() {
			return fmt("#\\{{},jdbcType={}\\}",
					this.key,
					this.jdbcType);
		}

		public final String buildFilter(String factor, String keyPrefix) {
			return fmt("`{}` {} {}",
					this.sqlClName,
					factor,
					this.buildSep(keyPrefix));
		}

		public final String buildSep(String keyPrefix) {
			return fmt("#\\{{}.{},jdbcType={}\\}",
					keyPrefix,
					this.key,
					this.jdbcType);
		}
    }

    public final static class TableItem {
    	public final String tableType;
    	public final String tableAttr;
    	public final String comment;
		public TableItem(String tableType, String tableAttr, String comment) {
			super();
			this.tableType = tableType;
			this.tableAttr = tableAttr;
			this.comment = comment;
		}
		public static TableItem of(String tableType, String tableAttr, String comment) {
			return new TableItem(tableType, tableAttr, comment);
		}
    }
}
