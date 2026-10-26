package dev.conduit.server.authorization;

import com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;

/** 分页、计数、普通Mapper、关联查询统一在 SQL 执行前追加授权范围。解析失败直接失败关闭。 */
public class ScopeDataPermissionHandler implements MultiDataPermissionHandler {
    /** 将可信 ID 范围转为解析后的 SQL 条件；解析异常向上抛出，禁止忽略过滤后继续查询。 */
    @Override public Expression getSqlSegment(Table table,Expression where,String statement) {
        var state = AuthorizationContext.current();
        if (state == null) return null;
        String name=table.getName();
        String qualifier=table.getAlias()==null ? name : table.getAlias().getName();
        String sql=state.scope().predicate(name,qualifier,state.permission());
        if(sql==null) return null;
        try { return CCJSqlParserUtil.parseCondExpression(sql); }
        catch(Exception e) { throw new IllegalStateException("授权范围 SQL 无法解析",e); }
    }
}
