package org.batfish.minesweeper.bdd;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.sf.javabdd.BDD;
import net.sf.javabdd.BDDFactory;
import net.sf.javabdd.JFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.batfish.common.BatfishException;
import org.batfish.common.bdd.MutableBDDInteger;
import org.batfish.datamodel.AsPathAccessList;
import org.batfish.datamodel.AsPathAccessListLine;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.IntegerSpace;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.LineAction;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.PrefixRange;
import org.batfish.datamodel.RouteFilterLine;
import org.batfish.datamodel.RouteFilterList;
import org.batfish.datamodel.RoutingProtocol;
import org.batfish.datamodel.SubRange;
import org.batfish.datamodel.ospf.OspfMetricType;
import org.batfish.datamodel.routing_policy.Environment;
import org.batfish.datamodel.routing_policy.RoutingPolicy;
import org.batfish.datamodel.routing_policy.as_path.InputAsPath;
import org.batfish.datamodel.routing_policy.as_path.MatchAsPath;
import org.batfish.datamodel.routing_policy.communities.InputCommunities;
import org.batfish.datamodel.routing_policy.communities.MatchCommunities;
import org.batfish.datamodel.routing_policy.communities.SetCommunities;
import org.batfish.datamodel.routing_policy.expr.AsPathSetExpr;
import org.batfish.datamodel.routing_policy.expr.BooleanExpr;
import org.batfish.datamodel.routing_policy.expr.BooleanExprs;
import org.batfish.datamodel.routing_policy.expr.CallExpr;
import org.batfish.datamodel.routing_policy.expr.Conjunction;
import org.batfish.datamodel.routing_policy.expr.ConjunctionChain;
import org.batfish.datamodel.routing_policy.expr.DestinationNetwork;
import org.batfish.datamodel.routing_policy.expr.DiscardNextHop;
import org.batfish.datamodel.routing_policy.expr.Disjunction;
import org.batfish.datamodel.routing_policy.expr.ExplicitPrefixSet;
import org.batfish.datamodel.routing_policy.expr.FirstMatchChain;
import org.batfish.datamodel.routing_policy.expr.IntComparator;
import org.batfish.datamodel.routing_policy.expr.IntExpr;
import org.batfish.datamodel.routing_policy.expr.IpNextHop;
import org.batfish.datamodel.routing_policy.expr.IpPrefix;
import org.batfish.datamodel.routing_policy.expr.LegacyMatchAsPath;
import org.batfish.datamodel.routing_policy.expr.LiteralInt;
import org.batfish.datamodel.routing_policy.expr.LiteralLong;
import org.batfish.datamodel.routing_policy.expr.LongExpr;
import org.batfish.datamodel.routing_policy.expr.MatchIpv4;
import org.batfish.datamodel.routing_policy.expr.MatchPrefixSet;
import org.batfish.datamodel.routing_policy.expr.MatchProtocol;
import org.batfish.datamodel.routing_policy.expr.MatchTag;
import org.batfish.datamodel.routing_policy.expr.NamedAsPathSet;
import org.batfish.datamodel.routing_policy.expr.NamedPrefixSet;
import org.batfish.datamodel.routing_policy.expr.NextHopExpr;
import org.batfish.datamodel.routing_policy.expr.NextHopIp;
import org.batfish.datamodel.routing_policy.expr.Not;
import org.batfish.datamodel.routing_policy.expr.PrefixExpr;
import org.batfish.datamodel.routing_policy.expr.PrefixSetExpr;
import org.batfish.datamodel.routing_policy.expr.WithEnvironmentExpr;
import org.batfish.datamodel.routing_policy.statement.BufferedStatement;
import org.batfish.datamodel.routing_policy.statement.CallStatement;
import org.batfish.datamodel.routing_policy.statement.If;
import org.batfish.datamodel.routing_policy.statement.SetDefaultPolicy;
import org.batfish.datamodel.routing_policy.statement.SetLocalPreference;
import org.batfish.datamodel.routing_policy.statement.SetMetric;
import org.batfish.datamodel.routing_policy.statement.SetNextHop;
import org.batfish.datamodel.routing_policy.statement.SetOrigin;
import org.batfish.datamodel.routing_policy.statement.SetOspfMetricType;
import org.batfish.datamodel.routing_policy.statement.SetTag;
import org.batfish.datamodel.routing_policy.statement.SetWeight;
import org.batfish.datamodel.routing_policy.statement.Statement;
import org.batfish.datamodel.routing_policy.statement.Statements.StaticStatement;
import org.batfish.datamodel.routing_policy.statement.TraceableStatement;
import org.batfish.minesweeper.CommunityVar;
import org.batfish.minesweeper.ConfigAtomicPredicates;
import org.batfish.minesweeper.OspfType;
import org.batfish.minesweeper.SymbolicAsPathRegex;
import org.batfish.minesweeper.SymbolicRegex;
import org.batfish.minesweeper.bdd.CommunitySetMatchExprToBDD.Arg;
import org.batfish.minesweeper.question.searchroutepolicies.SearchRoutePoliciesQuestion;
import org.batfish.minesweeper.utils.PrefixUtils;

/**
 * @author Ryan Beckett
 */
public class TransferBDD {

  private static final Logger LOGGER = LogManager.getLogger(SearchRoutePoliciesQuestion.class);

  /**
   * We track community and AS-path regexes by computing a set of atomic predicates for them. See
   * {@link org.batfish.minesweeper.RegexAtomicPredicates}. During the symbolic route analysis, we
   * simply need the map from each regex to its corresponding set of atomic predicates, each
   * represented by a unique integer.
   */
  private final Map<CommunityVar, Set<Integer>> _communityAtomicPredicates;

  private final Map<SymbolicAsPathRegex, Set<Integer>> _asPathRegexAtomicPredicates;

  private final Configuration _conf;

  private final RoutingPolicy _policy;

  private final ConfigAtomicPredicates _configAtomicPredicates;

  private Set<Prefix> _ignoredNetworks;

  private final List<Statement> _statements;

  private final BDDRoute _originalRoute;

  private final boolean _useOutputAttributes;

  private final BDDFactory _factory;

  public TransferBDD(ConfigAtomicPredicates aps, RoutingPolicy policy) {
    _configAtomicPredicates = aps;
    _policy = policy;
    _conf = policy.getOwner();
    _statements = policy.getStatements();
    _useOutputAttributes = Environment.useOutputAttributesFor(_conf);

    _factory = JFactory.init(100000, 10000);
    _factory.setCacheRatio(64);

    _originalRoute = new BDDRoute(_factory, aps);
    _communityAtomicPredicates =
        _configAtomicPredicates.getCommunityAtomicPredicates().getRegexAtomicPredicates();
    _asPathRegexAtomicPredicates =
        _configAtomicPredicates.getAsPathRegexAtomicPredicates().getRegexAtomicPredicates();
  }

  /*
   * Apply the effect of modifying a long value (e.g., to set the metric)
   */
  private MutableBDDInteger applyLongExprModification(
      TransferParam p, MutableBDDInteger x, LongExpr e) throws UnsupportedFeatureException {
    if (!(e instanceof LiteralLong)) {
      throw new UnsupportedFeatureException(e.toString());
    }
    LiteralLong z = (LiteralLong) e;
    p.debug("LiteralLong: %s", z.getValue());
    return MutableBDDInteger.makeFromValue(x.getFactory(), 32, z.getValue());

    /* TODO: These old cases are not correct; removing for now since they are not currently used.
    First, they should dec/inc the corresponding field of the route, not whatever MutableBDDInteger x
    is passed in.  Second, they need to prevent overflow.  See LongExpr::evaluate for details.

    if (e instanceof DecrementMetric) {
      DecrementMetric z = (DecrementMetric) e;
      p.debug("Decrement: %s", z.getSubtrahend());
      return x.sub(MutableBDDInteger.makeFromValue(x.getFactory(), 32, z.getSubtrahend()));
    }
    if (e instanceof IncrementMetric) {
      IncrementMetric z = (IncrementMetric) e;
      p.debug("Increment: %s", z.getAddend());
      return x.add(MutableBDDInteger.makeFromValue(x.getFactory(), 32, z.getAddend()));
    }
    if (e instanceof IncrementLocalPreference) {
      IncrementLocalPreference z = (IncrementLocalPreference) e;
      p.debug("IncrementLocalPreference: %s", z.getAddend());
      return x.add(MutableBDDInteger.makeFromValue(x.getFactory(), 32, z.getAddend()));
    }
    if (e instanceof DecrementLocalPreference) {
      DecrementLocalPreference z = (DecrementLocalPreference) e;
      p.debug("DecrementLocalPreference: %s", z.getSubtrahend());
      return x.sub(MutableBDDInteger.makeFromValue(x.getFactory(), 32, z.getSubtrahend()));
    }
     */
  }

  // produce a BDD that represents the disjunction of all atomic predicates associated with any of
  // the given as-path regexes
  BDD asPathRegexesToBDD(Set<SymbolicAsPathRegex> asPathRegexes, BDDRoute route) {
    Set<Integer> asPathAPs = atomicPredicatesFor(asPathRegexes, _asPathRegexAtomicPredicates);
    BDD[] apBDDs = route.getAsPathRegexAtomicPredicates();
    return route
        .getFactory()
        .orAll(asPathAPs.stream().map(ap -> apBDDs[ap]).collect(Collectors.toList()));
  }

  // produce the union of all atomic predicates associated with any of the given symbolic regexes
  <T extends SymbolicRegex> Set<Integer> atomicPredicatesFor(
      Set<T> regexes, Map<T, Set<Integer>> apMap) {
    return regexes.stream()
        .flatMap(r -> apMap.get(r).stream())
        .collect(ImmutableSet.toImmutableSet());
  }

  /**
   * Produces one TransferResult per path through the given boolean expression. For most
   * expressions, for example matching on a prefix or community, this analysis will yield exactly
   * two paths, respectively representing the case when the expression evaluates to true and false.
   * Some expressions, such as CallExpr, Conjunction, and Disjunction, implicitly or explicitly
   * contain branches and so can yield more than two paths. TODO: Any updates to the TransferParam
   * in expr are lost currently
   */
  private List<TransferResult> compute(BooleanExpr expr, TransferBDDState state)
      throws UnsupportedFeatureException {

    TransferParam p = state.getTransferParam();
    TransferResult result = state.getTransferResult();

    List<TransferResult> finalResults = new ArrayList<>();

    // TODO: right now everything is IPV4
    if (expr instanceof MatchIpv4) {
      p.debug("MatchIpv4 Result: true");
      finalResults.add(result.setReturnValueBDD(_factory.one()).setReturnValueAccepted(true));

    } else if (expr instanceof Not) {
      p.debug("mkNot");
      Not n = (Not) expr;
      List<TransferResult> results = compute(n.getExpr(), state);
      for (TransferResult res : results) {
        TransferResult newRes = res.setReturnValueAccepted(!res.getReturnValue().getAccepted());
        finalResults.add(newRes);
      }

    } else if (expr instanceof Conjunction) {
      Conjunction conj = (Conjunction) expr;
      List<TransferResult> currResults = new ArrayList<>();
      // the default result is true
      currResults.add(result.setReturnValueBDD(_factory.one()).setReturnValueAccepted(true));
      for (BooleanExpr e : conj.getConjuncts()) {
        List<TransferResult> nextResults = new ArrayList<>();
        for (TransferResult curr : currResults) {
          BDD currBDD = curr.getReturnValue().getSecond();
          compute(e, toTransferBDDState(p.indent(), curr))
              .forEach(
                  r -> {
                    TransferResult updated =
                        r.setReturnValueBDD(r.getReturnValue().getSecond().and(currBDD));
                    // if we're on a path where e evaluates to false, then this path is done;
                    // otherwise we will evaluate the next conjunct in the next iteration
                    if (!updated.getReturnValue().getAccepted()) {
                      finalResults.add(updated);
                    } else {
                      nextResults.add(updated);
                    }
                  });
        }
        currResults = nextResults;
      }
      finalResults.addAll(currResults);

    } else if (expr instanceof Disjunction) {
      Disjunction disj = (Disjunction) expr;
      List<TransferResult> currResults = new ArrayList<>();
      // the default result is false
      currResults.add(result.setReturnValueBDD(_factory.one()).setReturnValueAccepted(false));
      for (BooleanExpr e : disj.getDisjuncts()) {
        List<TransferResult> nextResults = new ArrayList<>();
        for (TransferResult curr : currResults) {
          BDD currBDD = curr.getReturnValue().getSecond();
          compute(e, toTransferBDDState(p.indent(), curr))
              .forEach(
                  r -> {
                    TransferResult updated =
                        r.setReturnValueBDD(r.getReturnValue().getSecond().and(currBDD));
                    // if we're on a path where e evaluates to true, then this path is done;
                    // otherwise we will evaluate the next disjunct in the next iteration
                    if (updated.getReturnValue().getAccepted()) {
                      finalResults.add(updated);
                    } else {
                      nextResults.add(updated);
                    }
                  });
        }
        currResults = nextResults;
      }
      finalResults.addAll(currResults);

      // TODO: This code is here for backward-compatibility reasons but has not been tested and is
      // not currently maintained
    } else if (expr instanceof ConjunctionChain) {
      p.debug("ConjunctionChain");
      ConjunctionChain d = (ConjunctionChain) expr;
      List<BooleanExpr> conjuncts = new ArrayList<>(d.getSubroutines());
      if (p.getDefaultPolicy() != null) {
        BooleanExpr be = new CallExpr(p.getDefaultPolicy().getDefaultPolicy());
        conjuncts.add(be);
      }
      if (conjuncts.isEmpty()) {
        finalResults.add(result.setReturnValueBDD(_factory.one()));
      } else {
        TransferParam record = p;
        List<TransferResult> currResults = new ArrayList<>();
        currResults.add(result);
        for (BooleanExpr e : d.getSubroutines()) {
          List<TransferResult> nextResults = new ArrayList<>();
          for (TransferResult curr : currResults) {
            TransferParam param =
                record
                    .setDefaultPolicy(null)
                    .setChainContext(TransferParam.ChainContext.CONJUNCTION)
                    .indent();
            compute(e, toTransferBDDState(param, curr))
                .forEach(
                    r -> {
                      if (r.getFallthroughValue().isOne()) {
                        nextResults.add(r);
                      } else {
                        finalResults.add(r);
                      }
                    });
          }
          currResults = nextResults;
        }
        finalResults.addAll(currResults);
      }

      // TODO: This code is here for backward-compatibility reasons but has not been tested and is
      // not currently maintained
    } else if (expr instanceof FirstMatchChain) {
      p.debug("FirstMatchChain");
      FirstMatchChain chain = (FirstMatchChain) expr;
      List<BooleanExpr> chainPolicies = new ArrayList<>(chain.getSubroutines());
      if (p.getDefaultPolicy() != null) {
        BooleanExpr be = new CallExpr(p.getDefaultPolicy().getDefaultPolicy());
        chainPolicies.add(be);
      }
      if (chainPolicies.isEmpty()) {
        // No identity for an empty FirstMatchChain; default policy should always be set.
        throw new BatfishException("Default policy is not set");
      }
      TransferParam record = p;
      List<TransferResult> currResults = new ArrayList<>();
      currResults.add(result);
      for (BooleanExpr e : chainPolicies) {
        List<TransferResult> nextResults = new ArrayList<>();
        for (TransferResult curr : currResults) {
          TransferParam param =
              record
                  .setDefaultPolicy(null)
                  .setChainContext(TransferParam.ChainContext.CONJUNCTION)
                  .indent();
          compute(e, toTransferBDDState(param, curr))
              .forEach(
                  r -> {
                    if (r.getFallthroughValue().isOne()) {
                      nextResults.add(r);
                    } else {
                      finalResults.add(r);
                    }
                  });
        }
        currResults = nextResults;
      }
      finalResults.addAll(currResults);

    } else if (expr instanceof MatchProtocol) {
      MatchProtocol mp = (MatchProtocol) expr;
      Set<RoutingProtocol> rps = mp.getProtocols();
      BDD matchRPBDD = _originalRoute.anyProtocolIn(rps);
      finalResults.add(result.setReturnValueBDD(matchRPBDD).setReturnValueAccepted(true));

    } else if (expr instanceof MatchPrefixSet) {
      p.debug("MatchPrefixSet");
      MatchPrefixSet m = (MatchPrefixSet) expr;

      // MatchPrefixSet::evaluate obtains the prefix to match (either the destination network or
      // next-hop IP) from the original route, so we do the same here
      BDD prefixSet = matchPrefixSet(p.indent(), _conf, m, _originalRoute);
      finalResults.add(result.setReturnValueBDD(prefixSet).setReturnValueAccepted(true));

    } else if (expr instanceof CallExpr) {
      p.debug("CallExpr");
      CallExpr c = (CallExpr) expr;
      String name = c.getCalledPolicyName();
      RoutingPolicy pol = _conf.getRoutingPolicies().get(name);

      // save callee state
      BDD oldReturnAssigned = result.getReturnAssignedValue();

      TransferParam newParam =
          p.setCallContext(TransferParam.CallContext.EXPR_CALL).indent().enterScope(name);
      List<TransferBDDState> callStates =
          compute(
              pol.getStatements(),
              ImmutableList.of(
                  new TransferBDDState(
                      newParam,
                      result
                          .setReturnAssignedValue(_factory.zero())
                          .setExitAssignedValue(_factory.zero()))));

      for (TransferBDDState callState : callStates) {
        finalResults.add(
            callState
                .getTransferResult()
                // restore the callee state
                .setReturnAssignedValue(oldReturnAssigned));
      }

    } else if (expr instanceof WithEnvironmentExpr) {
      p.debug("WithEnvironmentExpr");
      // TODO: this is not correct
      WithEnvironmentExpr we = (WithEnvironmentExpr) expr;
      // TODO: postStatements() and preStatements()
      finalResults.addAll(compute(we.getExpr(), state));

    } else if (expr instanceof MatchCommunities) {
      p.debug("MatchCommunities");
      MatchCommunities mc = (MatchCommunities) expr;
      // we only handle the case where the expression being matched is just the input communities
      if (!mc.getCommunitySetExpr().equals(InputCommunities.instance())) {
        throw new UnsupportedFeatureException(mc.toString());
      }
      BDD mcPredicate =
          mc.getCommunitySetMatchExpr()
              .accept(
                  new CommunitySetMatchExprToBDD(), new Arg(this, routeForMatching(p.getData())));
      finalResults.add(result.setReturnValueBDD(mcPredicate).setReturnValueAccepted(true));

    } else if (expr instanceof MatchTag) {
      MatchTag mt = (MatchTag) expr;
      BDD mtBDD =
          matchIntComparison(mt.getCmp(), mt.getTag(), routeForMatching(p.getData()).getTag());
      finalResults.add(result.setReturnValueBDD(mtBDD).setReturnValueAccepted(true));

    } else if (expr instanceof BooleanExprs.StaticBooleanExpr) {
      BooleanExprs.StaticBooleanExpr b = (BooleanExprs.StaticBooleanExpr) expr;
      switch (b.getType()) {
        case CallExprContext:
          p.debug("CallExprContext");
          finalResults.add(
              result
                  .setReturnValueBDD(_factory.one())
                  .setReturnValueAccepted(
                      p.getCallContext() == TransferParam.CallContext.EXPR_CALL));
          break;
        case CallStatementContext:
          p.debug("CallStmtContext");
          finalResults.add(
              result
                  .setReturnValueBDD(_factory.one())
                  .setReturnValueAccepted(
                      p.getCallContext() == TransferParam.CallContext.STMT_CALL));
          break;
        case True:
          p.debug("True");
          finalResults.add(result.setReturnValueBDD(_factory.one()).setReturnValueAccepted(true));
          break;
        case False:
          p.debug("False");
          finalResults.add(result.setReturnValueBDD(_factory.one()).setReturnValueAccepted(false));
          break;
        default:
          throw new UnsupportedFeatureException(b.getType().toString());
      }

    } else if (expr instanceof LegacyMatchAsPath) {
      p.debug("MatchAsPath");
      LegacyMatchAsPath legacyMatchAsPathNode = (LegacyMatchAsPath) expr;
      BDD asPathPredicate =
          matchAsPathSetExpr(
              p.indent(), _conf, legacyMatchAsPathNode.getExpr(), routeForMatching(p.getData()));
      finalResults.add(result.setReturnValueBDD(asPathPredicate).setReturnValueAccepted(true));

    } else if (expr instanceof MatchAsPath
        && ((MatchAsPath) expr).getAsPathExpr().equals(InputAsPath.instance())) {
      MatchAsPath matchAsPath = (MatchAsPath) expr;
      BDD asPathPredicate =
          matchAsPath
              .getAsPathMatchExpr()
              .accept(new AsPathMatchExprToBDD(), new Arg(this, routeForMatching(p.getData())));
      finalResults.add(result.setReturnValueBDD(asPathPredicate).setReturnValueAccepted(true));

    } else {
      throw new UnsupportedFeatureException(expr.toString());
    }

    // in most cases above we have only provided the path corresponding to the predicate being true
    // so lastly, we add the path corresponding to the predicate being false

    // first get a predicate representing routes that don't go down any existing path that we've
    // created so far
    BDD unmatched =
        _factory
            .orAll(
                finalResults.stream()
                    .map(r -> r.getReturnValue().getSecond())
                    .collect(Collectors.toList()))
            .not();
    if (!unmatched.isZero()) {
      // then add a non-accepting path corresponding to that predicate
      TransferResult remaining =
          new TransferResult(new BDDRoute(result.getReturnValue().getFirst()))
              .setReturnValueBDD(unmatched)
              .setReturnValueAccepted(false);
      finalResults.add(remaining);
    }
    return ImmutableList.copyOf(finalResults);
  }

  /*
   * Symbolic analysis of a single route-policy statement.
   * Produces one TransferResult per path through the given statement.
   */
  private List<TransferBDDState> compute(Statement stmt, TransferBDDState state)
      throws UnsupportedFeatureException {
    TransferParam curP = state.getTransferParam();
    TransferResult result = state.getTransferResult();

    if (stmt instanceof StaticStatement) {
      StaticStatement ss = (StaticStatement) stmt;

      switch (ss.getType()) {
        case ExitAccept:
          curP.debug("ExitAccept");
          result = exitValue(result, true);
          return ImmutableList.of(toTransferBDDState(curP, result));

        case ReturnTrue:
          curP.debug("ReturnTrue");
          result = returnValue(result, true);
          return ImmutableList.of(toTransferBDDState(curP, result));

        case ExitReject:
          curP.debug("ExitReject");
          result = exitValue(result, false);
          return ImmutableList.of(toTransferBDDState(curP, result));

        case ReturnFalse:
          curP.debug("ReturnFalse");
          result = returnValue(result, false);
          return ImmutableList.of(toTransferBDDState(curP, result));

        case SetDefaultActionAccept:
          curP.debug("SetDefaultActionAccept");
          curP = curP.setDefaultAccept(true);
          return ImmutableList.of(toTransferBDDState(curP, result));

        case SetDefaultActionReject:
          curP.debug("SetDefaultActionReject");
          curP = curP.setDefaultAccept(false);
          return ImmutableList.of(toTransferBDDState(curP, result));

        case SetLocalDefaultActionAccept:
          curP.debug("SetLocalDefaultActionAccept");
          curP = curP.setDefaultAcceptLocal(true);
          return ImmutableList.of(toTransferBDDState(curP, result));

        case SetLocalDefaultActionReject:
          curP.debug("SetLocalDefaultActionReject");
          curP = curP.setDefaultAcceptLocal(false);
          return ImmutableList.of(toTransferBDDState(curP, result));

        case ReturnLocalDefaultAction:
          curP.debug("ReturnLocalDefaultAction");
          result = returnValue(result, curP.getDefaultAcceptLocal());
          return ImmutableList.of(toTransferBDDState(curP, result));

        case DefaultAction:
          curP.debug("DefaultAction");
          result = exitValue(result, curP.getDefaultAccept());
          return ImmutableList.of(toTransferBDDState(curP, result));

        case FallThrough:
          curP.debug("Fallthrough");
          result = fallthrough(result, true);
          return ImmutableList.of(toTransferBDDState(curP, result));

        case Return:
          curP.debug("Return");
          result = result.setReturnAssignedValue(_factory.one());
          return ImmutableList.of(toTransferBDDState(curP, result));

        case Suppress:
          curP.debug("Suppress");
          result = suppressedValue(result, true);
          return ImmutableList.of(toTransferBDDState(curP, result));

        case Unsuppress:
          curP.debug("Unsuppress");
          result = suppressedValue(result, false);
          return ImmutableList.of(toTransferBDDState(curP, result));

        default:
          throw new UnsupportedFeatureException(ss.getType().toString());
      }

    } else if (stmt instanceof If) {
      curP.debug("If");
      If i = (If) stmt;
      List<TransferResult> guardResults =
          compute(i.getGuard(), new TransferBDDState(curP.indent(), result));

      // for each path coming from the guard, symbolically execute the appropriate branch of the If
      List<TransferBDDState> newStates = new ArrayList<>();
      BDD currPathCondition = result.getReturnValue().getSecond();
      for (TransferResult guardResult : guardResults) {
        BDD pathCondition = currPathCondition.and(guardResult.getReturnValue().getSecond());
        if (pathCondition.isZero()) {
          // prune infeasible paths
          continue;
        }
        BDDRoute current = guardResult.getReturnValue().getFirst();
        boolean accepted = guardResult.getReturnValue().getAccepted();

        TransferParam pCopy = curP.indent().setData(current);
        List<Statement> branch = accepted ? i.getTrueStatements() : i.getFalseStatements();
        newStates.addAll(
            compute(
                branch,
                ImmutableList.of(
                    new TransferBDDState(
                        pCopy,
                        result.setReturnValue(
                            new TransferReturn(
                                current, pathCondition, result.getReturnValue().getAccepted()))))));
      }

      return ImmutableList.copyOf(newStates);

    } else if (stmt instanceof SetDefaultPolicy) {
      curP.debug("SetDefaultPolicy");
      curP = curP.setDefaultPolicy((SetDefaultPolicy) stmt);
      return ImmutableList.of(toTransferBDDState(curP, result));

    } else if (stmt instanceof SetMetric) {
      curP.debug("SetMetric");
      SetMetric sm = (SetMetric) stmt;
      LongExpr ie = sm.getMetric();
      MutableBDDInteger curMed = curP.getData().getMed();
      MutableBDDInteger med = applyLongExprModification(curP.indent(), curMed, ie);
      curP.getData().setMed(med);
      return ImmutableList.of(toTransferBDDState(curP, result));

    } else if (stmt instanceof SetOspfMetricType) {
      curP.debug("SetOspfMetricType");
      SetOspfMetricType somt = (SetOspfMetricType) stmt;
      OspfMetricType mt = somt.getMetricType();
      BDDDomain<OspfType> current = result.getReturnValue().getFirst().getOspfMetric();
      BDDDomain<OspfType> newValue = new BDDDomain<>(current);
      if (mt == OspfMetricType.E1) {
        curP.indent().debug("Value: E1");
        newValue.setValue(OspfType.E1);
      } else {
        curP.indent().debug("Value: E2");
        newValue.setValue(OspfType.E1);
      }
      curP.getData().setOspfMetric(newValue);
      return ImmutableList.of(toTransferBDDState(curP, result));

    } else if (stmt instanceof SetLocalPreference) {
      curP.debug("SetLocalPreference");
      SetLocalPreference slp = (SetLocalPreference) stmt;
      LongExpr ie = slp.getLocalPreference();
      MutableBDDInteger newValue =
          applyLongExprModification(curP.indent(), curP.getData().getLocalPref(), ie);
      curP.getData().setLocalPref(newValue);
      return ImmutableList.of(toTransferBDDState(curP, result));

    } else if (stmt instanceof SetTag) {
      curP.debug("SetTag");
      SetTag st = (SetTag) stmt;
      LongExpr ie = st.getTag();
      MutableBDDInteger currTag = curP.getData().getTag();
      MutableBDDInteger newValue = applyLongExprModification(curP.indent(), currTag, ie);
      curP.getData().setTag(newValue);
      return ImmutableList.of(toTransferBDDState(curP, result));

    } else if (stmt instanceof SetWeight) {
      curP.debug("SetWeight");
      SetWeight sw = (SetWeight) stmt;
      IntExpr ie = sw.getWeight();
      if (!(ie instanceof LiteralInt)) {
        throw new UnsupportedFeatureException(ie.toString());
      }
      LiteralInt z = (LiteralInt) ie;
      MutableBDDInteger currWeight = curP.getData().getWeight();
      MutableBDDInteger newValue =
          MutableBDDInteger.makeFromValue(currWeight.getFactory(), 16, z.getValue());
      curP.getData().setWeight(newValue);
      return ImmutableList.of(toTransferBDDState(curP, result));

    } else if (stmt instanceof SetCommunities) {
      curP.debug("SetCommunities");
      SetCommunities sc = (SetCommunities) stmt;
      org.batfish.datamodel.routing_policy.communities.CommunitySetExpr setExpr =
          sc.getCommunitySetExpr();
      // SetCommunitiesVisitor requires a BDDRoute that maps each community atomic predicate BDD
      // to its corresponding BDD variable, so we use the original route here
      CommunityAPDispositions dispositions =
          setExpr.accept(new SetCommunitiesVisitor(), new Arg(this, _originalRoute));
      updateCommunities(dispositions, curP);
      return ImmutableList.of(toTransferBDDState(curP, result));

    } else if (stmt instanceof CallStatement) {
      /*
       this code is based on the concrete semantics defined by CallStatement::execute, which also
       relies on RoutingPolicy::call
      */
      curP.debug("CallStatement");
      CallStatement cs = (CallStatement) stmt;
      String name = cs.getCalledPolicyName();
      RoutingPolicy pol = _conf.getRoutingPolicies().get(name);
      if (pol == null) {
        throw new BatfishException("Called route policy does not exist: " + name);
      }

      // save callee state
      BDD oldReturnAssigned = result.getReturnAssignedValue();

      TransferParam newParam =
          curP.indent().setCallContext(TransferParam.CallContext.STMT_CALL).enterScope(name);
      List<TransferBDDState> callResults =
          compute(
              pol.getStatements(),
              ImmutableList.of(
                  new TransferBDDState(newParam, result.setReturnAssignedValue(_factory.zero()))));
      // TODO: Currently dropping the returned TransferParam on the floor
      TransferParam finalCurP = curP;
      // restore the original returnAssigned value
      return callResults.stream()
          .map(
              r ->
                  toTransferBDDState(
                      finalCurP, r.getTransferResult().setReturnAssignedValue(oldReturnAssigned)))
          .collect(ImmutableList.toImmutableList());

    } else if (stmt instanceof BufferedStatement) {
      curP.debug("BufferedStatement");
      BufferedStatement bufStmt = (BufferedStatement) stmt;
      /**
       * The {@link Environment} class for simulating route policies keeps track of whether a
       * statement is buffered, but it currently does not seem to ever use that information. So we
       * ignore it.
       */
      return compute(bufStmt.getStatement(), state);

    } else if (stmt instanceof SetOrigin) {
      curP.debug("SetOrigin");
      // System.out.println("Warning: use of unimplemented feature SetOrigin");
      // TODO: implement me
      return ImmutableList.of(toTransferBDDState(curP, result));

    } else if (stmt instanceof SetNextHop) {
      curP.debug("SetNextHop");
      setNextHop(((SetNextHop) stmt).getExpr(), curP.getData());
      return ImmutableList.of(toTransferBDDState(curP, result));

    } else if (stmt instanceof TraceableStatement) {
      return compute(((TraceableStatement) stmt).getInnerStatements(), ImmutableList.of(state));

    } else {
      throw new UnsupportedFeatureException(stmt.toString());
    }
  }

  /*
   * Symbolic analysis of a list of route-policy statements.
   * Produces one TransferBDDState per path through the given list of statements.
   */
  private List<TransferBDDState> compute(
      List<Statement> statements, List<TransferBDDState> states) {
    List<TransferBDDState> currStates = states;
    for (Statement stmt : statements) {
      List<TransferBDDState> newStates = new ArrayList<>();
      for (TransferBDDState currState : currStates) {
        try {
          // if the path has already reached an exit/return then just keep it
          if (unreachable(currState.getTransferResult()).isOne()) {
            newStates.add(currState);
          } else {
            // otherwise symbolically execute the next statement
            newStates.addAll(compute(stmt, currState));
          }
        } catch (UnsupportedFeatureException e) {
          unsupported(stmt, currState);
          newStates.add(currState);
        }
      }
      currStates = newStates;
    }
    return currStates;
  }

  /**
   * Symbolic analysis of a list of route-policy statements. Returns one TransferResult per path
   * through the list of statements. The list of paths is unordered, and by construction each path
   * is unique, as each path has a unique condition under which it is taken (the BDD in the
   * TransferResult). The particular statements executed along a given path are not included in this
   * representation but can be reconstructed by simulating one route that takes this path using
   * {@link org.batfish.question.testroutepolicies.TestRoutePoliciesQuestion}.
   */
  private List<TransferResult> computePaths(List<Statement> statements, TransferParam p) {
    TransferParam curP = p;

    TransferResult result = new TransferResult(curP.getData());

    List<TransferBDDState> states =
        compute(statements, ImmutableList.of(new TransferBDDState(curP, result)));

    ImmutableList.Builder<TransferResult> results = ImmutableList.builder();
    for (TransferBDDState state : states) {
      curP = state.getTransferParam();
      result = state.getTransferResult();
      if (result.getReturnValue().getSecond().isZero()) {
        // ignore infeasible paths
        continue;
      }
      curP.debug("InitialCall finalizing");
      TransferReturn ret = result.getReturnValue();
      // Only accept routes that are not suppressed
      BDD finalAccepts = ret.getSecond().diff(result.getSuppressedValue());
      result = result.setReturnValueBDD(finalAccepts);
      results.add(result);
    }
    return results.build();
  }

  /**
   * Combines the per-path symbolic analysis results into a single TransferResult, which represents
   * the disjunction of all accepting paths. *
   */
  private TransferResult compute(List<Statement> statements, TransferParam p) {
    List<TransferResult> allPaths = computePaths(statements, p);
    // by default the result says that there are no feasible paths
    TransferResult result =
        new TransferResult(
            new TransferReturn(new BDDRoute(_factory, _configAtomicPredicates), _factory.zero()),
            _factory.zero());
    // now disjoin all of the accepting paths
    for (TransferResult path : allPaths) {
      if (path.getReturnValue().getAccepted()) {
        result = ite(path.getReturnValue().getSecond(), path, result);
      } else {
        // for denying paths, we still keep track of whether we hit an unsupported statement
        BDDRoute resultRoute = result.getReturnValue().getFirst();
        BDDRoute pathRoute = path.getReturnValue().getFirst();
        resultRoute.setUnsupported(
            ite(
                path.getReturnValue().getSecond(),
                pathRoute.getUnsupported(),
                resultRoute.getUnsupported()));
      }
    }
    return result;
  }

  private TransferResult fallthrough(TransferResult r, boolean val) {
    BDD fall = mkBDD(val);
    return r.setFallthroughValue(fall).setReturnAssignedValue(_factory.one());
  }

  // Create a TransferBDDState, using the BDDRoute in the given TransferResult and throwing away the
  // one that is in the given TransferParam.
  private TransferBDDState toTransferBDDState(TransferParam curP, TransferResult result) {
    return new TransferBDDState(curP.setData(result.getReturnValue().getFirst()), result);
  }

  // Produce a BDD representing conditions under which the route's destination prefix is within a
  // given prefix range.
  public static BDD isRelevantForDestination(BDDRoute record, PrefixRange range) {
    SubRange r = range.getLengthRange();
    int lower = r.getStart();
    int upper = r.getEnd();

    BDD prefixMatch = record.getPrefix().toBDD(range.getPrefix());
    BDD lenMatch = record.getPrefixLength().range(lower, upper);
    return prefixMatch.and(lenMatch);
  }

  // Produce a BDD representing conditions under which the route's next-hop address is within a
  // given prefix range.
  private static BDD isRelevantForNextHop(BDDRoute record, PrefixRange range) {
    return record.getNextHop().toBDD(range.getPrefix());
  }

  /*
   * If-then-else statement
   */
  private BDD ite(BDD b, BDD x, BDD y) {
    return b.ite(x, y);
  }

  /*
   * Map ite over MutableBDDInteger type
   */
  private MutableBDDInteger ite(BDD b, MutableBDDInteger x, MutableBDDInteger y) {
    return x.ite(b, y);
  }

  /*
   * Map ite over BDDDomain type
   */
  private <T> BDDDomain<T> ite(BDD b, BDDDomain<T> x, BDDDomain<T> y) {
    BDDDomain<T> result = new BDDDomain<>(x);
    MutableBDDInteger i = ite(b, x.getInteger(), y.getInteger());
    result.setInteger(i);
    return result;
  }

  @VisibleForTesting
  BDDRoute ite(BDD guard, BDDRoute r1, BDDRoute r2) {
    BDDRoute ret =
        new BDDRoute(
            _factory,
            _configAtomicPredicates.getCommunityAtomicPredicates().getNumAtomicPredicates(),
            _configAtomicPredicates.getAsPathRegexAtomicPredicates().getNumAtomicPredicates());

    MutableBDDInteger x;
    MutableBDDInteger y;

    // update integer values based on condition
    // x = r1.getPrefixLength();
    // y = r2.getPrefixLength();
    // ret.getPrefixLength().setValue(ite(guard, x, y));

    // x = r1.getIp();
    // y = r2.getIp();
    // ret.getIp().setValue(ite(guard, x, y));

    x = r1.getAdminDist();
    y = r2.getAdminDist();
    ret.getAdminDist().setValue(ite(guard, x, y));

    x = r1.getLocalPref();
    y = r2.getLocalPref();
    ret.getLocalPref().setValue(ite(guard, x, y));

    x = r1.getMed();
    y = r2.getMed();
    ret.getMed().setValue(ite(guard, x, y));

    x = r1.getNextHop();
    y = r2.getNextHop();
    ret.getNextHop().setValue(ite(guard, x, y));

    ret.setNextHopDiscarded(ite(guard, r1.getNextHopDiscarded(), r2.getNextHopDiscarded()));
    ret.setNextHopSet(ite(guard, r1.getNextHopSet(), r2.getNextHopSet()));

    x = r1.getTag();
    y = r2.getTag();
    ret.getTag().setValue(ite(guard, x, y));

    x = r1.getWeight();
    y = r2.getWeight();
    ret.getWeight().setValue(ite(guard, x, y));

    BDD[] retCommAPs = ret.getCommunityAtomicPredicates();
    BDD[] r1CommAPs = r1.getCommunityAtomicPredicates();
    BDD[] r2CommAPs = r2.getCommunityAtomicPredicates();
    for (int i = 0;
        i < _configAtomicPredicates.getCommunityAtomicPredicates().getNumAtomicPredicates();
        i++) {
      retCommAPs[i] = ite(guard, r1CommAPs[i], r2CommAPs[i]);
    }
    BDD[] retAsPathRegexAPs = ret.getAsPathRegexAtomicPredicates();
    BDD[] r1AsPathRegexAPs = r1.getAsPathRegexAtomicPredicates();
    BDD[] r2AsPathRegexAPs = r2.getAsPathRegexAtomicPredicates();
    for (int i = 0;
        i < _configAtomicPredicates.getAsPathRegexAtomicPredicates().getNumAtomicPredicates();
        i++) {
      retAsPathRegexAPs[i] = ite(guard, r1AsPathRegexAPs[i], r2AsPathRegexAPs[i]);
    }

    ret.setUnsupported(ite(guard, r1.getUnsupported(), r2.getUnsupported()));

    // MutableBDDInteger i =
    //    ite(guard, r1.getProtocolHistory().getInteger(), r2.getProtocolHistory().getInteger());
    // ret.getProtocolHistory().setInteger(i);

    return ret;
  }

  TransferResult ite(BDD guard, TransferResult r1, TransferResult r2) {
    BDDRoute route = ite(guard, r1.getReturnValue().getFirst(), r2.getReturnValue().getFirst());
    BDD accepted = ite(guard, r1.getReturnValue().getSecond(), r2.getReturnValue().getSecond());

    BDD suppressed = ite(guard, r1.getSuppressedValue(), r2.getSuppressedValue());
    BDD exitAsgn = ite(guard, r1.getExitAssignedValue(), r2.getExitAssignedValue());
    BDD retAsgn = ite(guard, r1.getReturnAssignedValue(), r2.getReturnAssignedValue());
    BDD fallThrough = ite(guard, r1.getFallthroughValue(), r2.getFallthroughValue());

    return new TransferResult(
        new TransferReturn(route, accepted), suppressed, exitAsgn, fallThrough, retAsgn);
  }

  // Produce a BDD that is the symbolic representation of the given AsPathSetExpr predicate.
  private BDD matchAsPathSetExpr(
      TransferParam p, Configuration conf, AsPathSetExpr e, BDDRoute other)
      throws UnsupportedFeatureException {
    if (e instanceof NamedAsPathSet) {
      NamedAsPathSet namedAsPathSet = (NamedAsPathSet) e;
      AsPathAccessList accessList = conf.getAsPathAccessLists().get(namedAsPathSet.getName());
      p.debug("Named As Path Set: %s", namedAsPathSet.getName());
      return matchAsPathAccessList(accessList, other);
    }
    // TODO: handle other kinds of AsPathSetExprs
    throw new UnsupportedFeatureException(e.toString());
  }

  /* Convert an AS-path access list to a boolean formula represented as a BDD. */
  private BDD matchAsPathAccessList(AsPathAccessList accessList, BDDRoute other) {
    List<AsPathAccessListLine> lines = new ArrayList<>(accessList.getLines());
    Collections.reverse(lines);
    BDD acc = _factory.zero();
    for (AsPathAccessListLine line : lines) {
      boolean action = (line.getAction() == LineAction.PERMIT);
      // each line's regex is represented as the disjunction of all of the regex's
      // corresponding atomic predicates
      SymbolicAsPathRegex regex = new SymbolicAsPathRegex(line.getRegex());
      BDD regexAPBdd = asPathRegexesToBDD(ImmutableSet.of(regex), other);
      acc = ite(regexAPBdd, mkBDD(action), acc);
    }
    return acc;
  }

  /*
   * Converts a route filter list to a boolean expression.
   */
  private BDD matchFilterList(
      TransferParam p,
      RouteFilterList x,
      BDDRoute other,
      BiFunction<BDDRoute, PrefixRange, BDD> symbolicMatcher)
      throws UnsupportedFeatureException {
    BDD acc = _factory.zero();
    List<RouteFilterLine> lines = new ArrayList<>(x.getLines());
    Collections.reverse(lines);
    for (RouteFilterLine line : lines) {
      if (!line.getIpWildcard().isPrefix()) {
        throw new UnsupportedFeatureException(line.getIpWildcard().toString());
      }
      Prefix pfx = line.getIpWildcard().toPrefix();
      if (!PrefixUtils.isContainedBy(pfx, _ignoredNetworks)) {
        SubRange r = line.getLengthRange();
        PrefixRange range = new PrefixRange(pfx, r);
        p.debug("Prefix Range: %s", range);
        p.debug("Action: %s", line.getAction());
        BDD matches = symbolicMatcher.apply(other, range);
        BDD action = mkBDD(line.getAction() == LineAction.PERMIT);
        acc = ite(matches, action, acc);
      }
    }
    return acc;
  }

  // Returns a function that can convert a prefix range into a BDD that constrains the appropriate
  // part of a route (destination prefix or next-hop IP), depending on the given prefix
  // expression.
  private BiFunction<BDDRoute, PrefixRange, BDD> prefixExprToSymbolicMatcher(PrefixExpr pe)
      throws UnsupportedFeatureException {
    if (pe.equals(DestinationNetwork.instance())) {
      return TransferBDD::isRelevantForDestination;
    } else if (pe instanceof IpPrefix) {
      IpPrefix ipp = (IpPrefix) pe;
      if (ipp.getIp().equals(NextHopIp.instance())
          && ipp.getPrefixLength().equals(new LiteralInt(Prefix.MAX_PREFIX_LENGTH))) {
        return TransferBDD::isRelevantForNextHop;
      }
    }
    throw new UnsupportedFeatureException(pe.toString());
  }

  /*
   * Converts a prefix set to a boolean expression.
   */
  private BDD matchPrefixSet(TransferParam p, Configuration conf, MatchPrefixSet m, BDDRoute other)
      throws UnsupportedFeatureException {
    BiFunction<BDDRoute, PrefixRange, BDD> symbolicMatcher =
        prefixExprToSymbolicMatcher(m.getPrefix());
    PrefixSetExpr e = m.getPrefixSet();
    if (e instanceof ExplicitPrefixSet) {
      ExplicitPrefixSet x = (ExplicitPrefixSet) e;

      Set<PrefixRange> ranges = x.getPrefixSpace().getPrefixRanges();
      BDD acc = _factory.zero();
      for (PrefixRange range : ranges) {
        p.debug("Prefix Range: %s", range);
        if (!PrefixUtils.isContainedBy(range.getPrefix(), _ignoredNetworks)) {
          acc = acc.or(symbolicMatcher.apply(other, range));
        }
      }
      return acc;

    } else if (e instanceof NamedPrefixSet) {
      NamedPrefixSet x = (NamedPrefixSet) e;
      p.debug("Named: %s", x.getName());
      String name = x.getName();
      RouteFilterList fl = conf.getRouteFilterLists().get(name);
      return matchFilterList(p, fl, other, symbolicMatcher);

    } else {
      throw new UnsupportedFeatureException(e.toString());
    }
  }

  // Produce a BDD representing a constraint on the given MutableBDDInteger that enforces the
  // integer (in)equality constraint represented by the given IntComparator and LongExpr
  private BDD matchIntComparison(IntComparator comp, LongExpr expr, MutableBDDInteger bddInt)
      throws UnsupportedFeatureException {
    if (!(expr instanceof LiteralLong)) {
      throw new UnsupportedFeatureException(expr.toString());
    }
    long val = ((LiteralLong) expr).getValue();
    switch (comp) {
      case EQ:
        return bddInt.value(val);
      case GE:
        return bddInt.geq(val);
      case GT:
        return bddInt.geq(val).and(bddInt.value(val).not());
      case LE:
        return bddInt.leq(val);
      case LT:
        return bddInt.leq(val).and(bddInt.value(val).not());
      default:
        throw new UnsupportedFeatureException(comp.getClass().getSimpleName());
    }
  }

  /*
   * Return a BDD from a boolean
   */
  BDD mkBDD(boolean b) {
    return b ? _factory.one() : _factory.zero();
  }

  private void setNextHop(NextHopExpr expr, BDDRoute route) throws UnsupportedFeatureException {
    if (expr instanceof DiscardNextHop) {
      route.setNextHopDiscarded(_factory.one());
    } else if (expr instanceof IpNextHop && ((IpNextHop) expr).getIps().size() == 1) {
      List<Ip> ips = ((IpNextHop) expr).getIps();
      Ip ip = ips.get(0);
      route.setNextHop(MutableBDDInteger.makeFromValue(_factory, 32, ip.asLong()));
    } else {
      throw new UnsupportedFeatureException(expr.toString());
    }
    // record the fact that the next-hop has been explicitly set by the route-map
    route.setNextHopSet(_factory.one());
  }

  // Set the corresponding BDDs of the given community atomic predicates to either 1 or 0,
  // depending on the value of the boolean parameter.
  private void addOrRemoveCommunityAPs(IntegerSpace commAPs, TransferParam curP, boolean add) {
    BDD newCommVal = mkBDD(add);
    BDD[] commAPBDDs = curP.getData().getCommunityAtomicPredicates();
    for (int ap : commAPs.enumerate()) {
      curP.indent().debug("Value: %s", ap);
      curP.indent().debug("New Value: %s", newCommVal);
      commAPBDDs[ap] = newCommVal;
    }
  }

  // Update community atomic predicates based on the given CommunityAPDispositions object
  private void updateCommunities(CommunityAPDispositions dispositions, TransferParam curP) {
    addOrRemoveCommunityAPs(dispositions.getMustExist(), curP, true);
    addOrRemoveCommunityAPs(dispositions.getMustNotExist(), curP, false);
  }

  /**
   * A BDD representing the conditions under which the current statement is not reachable, because
   * we've already returned or exited before getting there.
   *
   * @param currState the current state of the analysis
   * @return the bdd
   */
  private static BDD unreachable(TransferResult currState) {
    return currState.getReturnAssignedValue().or(currState.getExitAssignedValue());
  }

  // If the analysis encounters a routing policy feature that is not currently supported, we ignore
  // it and keep going, but we also log a warning and mark the output BDDRoute as having reached an
  // unsupported statement.
  private void unsupported(Statement stmt, TransferBDDState state) {
    LOGGER.warn(
        "Unsupported statement in routing policy "
            + _policy.getName()
            + " of node "
            + _conf.getHostname()
            + ": "
            + stmt);
    TransferParam curP = state.getTransferParam();
    curP.getData().setUnsupported(_factory.one());
  }

  /*
   * Create the result of reaching a suppress or unsuppress statement.
   */
  private TransferResult suppressedValue(TransferResult r, boolean val) {
    BDD b = mkBDD(val);
    return r.setSuppressedValue(b);
  }

  /*
   * Create the result of reaching a return statement, returning with the given value.
   */
  private TransferResult returnValue(TransferResult r, boolean accepted) {
    return r.setReturnValue(r.getReturnValue().setAccepted(accepted))
        .setReturnAssignedValue(_factory.one());
  }

  /*
   * Create the result of reaching an exit statement, returning with the given value.
   */
  private TransferResult exitValue(TransferResult r, boolean accepted) {
    return r.setReturnValue(r.getReturnValue().setAccepted(accepted))
        .setExitAssignedValue(_factory.one());
  }

  // Returns the appropriate route to use for matching on attributes.
  private BDDRoute routeForMatching(BDDRoute current) {
    return _useOutputAttributes ? current : _originalRoute;
  }

  /*
   * Create a TransferResult representing the symbolic output of
   * the RoutingPolicy given the input route.
   */
  public TransferResult compute(@Nullable Set<Prefix> ignoredNetworks) {
    _ignoredNetworks = ignoredNetworks;
    BDDRoute o = new BDDRoute(_factory, _configAtomicPredicates);
    TransferParam p = new TransferParam(o, false);
    return compute(_statements, p);
  }

  /**
   * The results of symbolic route-map analysis: one {@link
   * org.batfish.minesweeper.bdd.TransferReturn} per execution path through the given route map. The
   * list of paths is unordered, and by construction each path is unique, as each path has a unique
   * condition under which it is taken (the BDD in the TransferResult). The particular statements
   * executed along a given path are not included in this representation but can be reconstructed by
   * simulating one route that takes this path using {@link
   * org.batfish.question.testroutepolicies.TestRoutePoliciesQuestion}.
   */
  public List<TransferReturn> computePaths(@Nullable Set<Prefix> ignoredNetworks) {
    _ignoredNetworks = ignoredNetworks;
    BDDRoute o = new BDDRoute(_factory, _configAtomicPredicates);
    TransferParam p = new TransferParam(o, false);
    return computePaths(_statements, p).stream()
        .map(TransferResult::getReturnValue)
        .collect(ImmutableList.toImmutableList());
  }

  public Map<CommunityVar, Set<Integer>> getCommunityAtomicPredicates() {
    return _communityAtomicPredicates;
  }

  public Configuration getConfiguration() {
    return _conf;
  }

  public BDDFactory getFactory() {
    return _factory;
  }

  public ConfigAtomicPredicates getGraph() {
    return _configAtomicPredicates;
  }

  public boolean getUseOutputAttributes() {
    return _useOutputAttributes;
  }
}
