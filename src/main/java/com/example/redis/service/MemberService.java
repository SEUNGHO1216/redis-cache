package com.example.redis.service;

import com.example.redis.Member;
import com.example.redis.dto.MemberDTO;
import com.example.redis.mapper.MemberMapper;
import com.example.redis.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MemberService {

  private final ApplicationContext applicationContext;

  private final MemberRepository memberRepository;
  private final MemberMapper memberMapper;
  private final RedisTemplate redisTemplate;

  @Cacheable(value = "member", unless = "#result == null", cacheManager = "cacheManager")
  public List<MemberDTO> getMemberList() {
    log.error("finding member list try..");

    List<MemberDTO> members = memberRepository.findAll().stream()
      .map(memberMapper::toDto)
      .collect(Collectors.toList());

    return members;
  }

  // redis template을 통한 직접 redis key 등록 -> 그러나 개별 조회에서 캐싱을 사용하지 않을거면 별로 필요 없을 듯
  public List<MemberDTO> getMemberListByRedisTemplate() {
    List<Member> members = memberRepository.findAll();
    Map<Long, MemberDTO> membersMap = memberRepository.findAll().stream()
      .collect(Collectors.toMap(Member::getId, memberMapper::toDto));
    Set<Map.Entry<Long, MemberDTO>> entries = membersMap.entrySet();
    Iterator<Map.Entry<Long, MemberDTO>> iterator = entries.iterator();
    ValueOperations valueOperations = redisTemplate.opsForValue();
    while (iterator.hasNext()) {
      Map.Entry<Long, MemberDTO> next = iterator.next();
      valueOperations.set("member::" + next.getKey(), next.getValue(), Duration.ofSeconds(100));
    }
    return members.stream()
      .map(memberMapper::toDto)
      .collect(Collectors.toList());
  }

  // spring proxy bean을 호출해 내부호출 문제 해결 후
  // 개별 id로 조회하여 캐시 데이터 불러오는 형식
  public MemberDTO getMemberByProxy(Long id) {
    List<MemberDTO> memberDTOS = _getProxyBean().getMemberList();
    log.error("finding member try..");
    return memberDTOS.stream()
      .filter(member -> member.getId().equals(id))
      .findAny()
      .orElseThrow(NoSuchElementException::new);
  }

  // redis template 을 활용하여 캐싱 데이터 사용
  public MemberDTO getMemberFromRedis(Long keyId) {
    String key = "member::" + keyId;
    log.error("key >>{}", key);
    ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
    if (valueOperations.get(key) == null) {
      throw new IllegalArgumentException("There is not redis key..");
    }
    MemberDTO member = (MemberDTO) valueOperations.get(key);
    log.error("redis member.getId() >>{}", member.getId());
    log.error("redis member.getUsername() >>{}", member.getUsername());
    log.error("redis member.getTelephone() >>{}", member.getTelephone());

    return member;
  }

  public Set<String> showAllKeysByScanning() {

    // keys* 의 자바화
    Set<String> keys = redisTemplate.keys("*");
    Iterator<String> iterator = keys.iterator();
    while (iterator.hasNext()) {
      log.error("iterator.next() >> {}", iterator.next());
    }

    // scan 으로 keys* 대체
    Set<String> result = new HashSet<>();
    RedisConnectionFactory connectionFactory = redisTemplate.getConnectionFactory();
    RedisConnection connection = connectionFactory.getConnection();
    ScanOptions options = ScanOptions.scanOptions().match("*").build();

    Cursor<byte[]> cursor = connection.scan(options);

    while (cursor.hasNext()) {
      byte[] next = cursor.next();
      String matchedKey = new String(next, StandardCharsets.UTF_8);
      log.error("matchedKey >> {}", matchedKey);
      result.add(matchedKey);
    }
    return result;
  }

  // self-invocation 시 캐싱 처리 못하는 부분 해결(프록시로 만들어진 빈을 IOC 컨테이너에서 갖다 씀)
  private MemberService _getProxyBean() {
    return applicationContext.getBean(MemberService.class);
  }

  @CacheEvict(cacheNames = "member")
  public void cacheReset(){
    log.error("cache reset!");
  }

  public List<MemberDTO> cacheRenewal(){
    this._getProxyBean().cacheReset();
    return this._getProxyBean().getMemberList();
  }

  // =====데이터 CUD=====
  public MemberDTO createMember(MemberDTO memberDTO) {
    return
      Optional.of(
          Member.create(
            memberDTO.getUsername(),
            memberDTO.getTelephone(),
            memberDTO.getAge(),
            memberDTO.getGender())
        ).map(memberRepository::save)
        .map(memberMapper::toDto)
        .get();
  }

  @Transactional
  public MemberDTO updateMember(Long id, MemberDTO memberDTO) {
    return
      memberRepository.findById(id)
        .map(member -> member.update(
          memberDTO.getUsername(),
          memberDTO.getTelephone(),
          memberDTO.getAge(),
          memberDTO.getGender()
        ))
        .map(memberMapper::toDto)
        .orElseThrow(NoSuchElementException::new);
  }

  @Transactional
  public Long deleteMember(Long id) {
    memberRepository.deleteById(
      memberRepository.findById(id).orElseThrow(NoSuchElementException::new).getId()
    );
    return id;
  }
}
