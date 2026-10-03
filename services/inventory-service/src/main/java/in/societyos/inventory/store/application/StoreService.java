package in.societyos.inventory.store.application;

import in.societyos.inventory.common.Texts;
import in.societyos.inventory.platform.core.error.ProblemException;
import in.societyos.inventory.store.domain.Store;
import in.societyos.inventory.store.infrastructure.StoreRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Store rooms. The first store of a society becomes its default. */
@Service
public class StoreService {

  public record StoreCommand(String code, String name, UUID locationId, UUID keeperUserId, Boolean makeDefault) {}

  private final StoreRepository stores;

  public StoreService(StoreRepository stores) {
    this.stores = stores;
  }

  @Transactional
  public Store create(StoreCommand c) {
    String code = Texts.code(c.code());
    if (code != null && stores.existsByCode(code)) {
      throw ProblemException.conflict("STORE_CODE_EXISTS", "Store code " + code + " is taken");
    }
    Optional<Store> current = stores.findByDefaultStoreTrue();
    boolean makeDefault = current.isEmpty() || Boolean.TRUE.equals(c.makeDefault());
    if (makeDefault && current.isPresent()) {
      current.get().makeDefault(false);
      stores.saveAndFlush(current.get());
    }
    return stores.save(Store.create(code, Texts.clean(c.name()), c.locationId(), c.keeperUserId(), makeDefault));
  }

  @Transactional
  public Store update(UUID id, StoreCommand c) {
    Store s = require(id);
    s.update(Texts.clean(c.name()), c.locationId(), c.keeperUserId());
    if (Boolean.TRUE.equals(c.makeDefault()) && !s.isDefaultStore()) {
      stores.findByDefaultStoreTrue().ifPresent(d -> {
        d.makeDefault(false);
        stores.saveAndFlush(d);
      });
      s.makeDefault(true);
    }
    return stores.save(s);
  }

  @Transactional
  public Store deactivate(UUID id) {
    Store s = require(id);
    s.deactivate();
    return stores.save(s);
  }

  @Transactional(readOnly = true)
  public List<Store> list() {
    return stores.findAllByOrderByCodeAsc();
  }

  @Transactional(readOnly = true)
  public Store require(UUID id) {
    return stores.findById(id).orElseThrow(() -> ProblemException.notFound("store", id));
  }

  /**
   * The store a GRN is booked into: the named one if it exists in this society, else the default
   * store, else a MAIN store created on the spot so a receipt is never lost.
   */
  @Transactional
  public Store forReceipt(UUID storeId) {
    if (storeId != null) {
      Optional<Store> named = stores.findById(storeId);
      if (named.isPresent()) {
        return named.get();
      }
    }
    return stores.findByDefaultStoreTrue()
        .orElseGet(() -> stores.save(Store.create(stores.existsByCode("MAIN") ? "MAIN-" + UUID.randomUUID()
            .toString().substring(0, 4).toUpperCase() : "MAIN", "Main store", null, null, true)));
  }
}
