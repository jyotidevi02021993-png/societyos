package in.societyos.inventory.item.application;

import in.societyos.inventory.common.Texts;
import in.societyos.inventory.item.domain.Item;
import in.societyos.inventory.item.infrastructure.ItemRepository;
import in.societyos.inventory.platform.core.error.ProblemException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The item (spare) catalogue. */
@Service
public class ItemService {

  public record ItemCommand(String code, String name, String kind, String category, String unit, Integer reorderLevel) {}

  private final ItemRepository items;

  public ItemService(ItemRepository items) {
    this.items = items;
  }

  @Transactional
  public Item create(ItemCommand c) {
    String code = Texts.code(c.code());
    if (code != null && items.existsByCode(code)) {
      throw ProblemException.conflict("ITEM_CODE_EXISTS", "Item code " + code + " is taken");
    }
    return items.save(Item.create(code, Texts.clean(c.name()), Texts.upper(c.kind()), Texts.upper(c.category()),
        Texts.upper(c.unit()), c.reorderLevel() == null ? 0 : c.reorderLevel()));
  }

  @Transactional
  public Item update(UUID id, ItemCommand c) {
    Item i = require(id);
    i.describe(Texts.clean(c.name()), Texts.upper(c.kind()), Texts.upper(c.category()), Texts.upper(c.unit()),
        c.reorderLevel() == null ? i.getDefaultReorderLevel() : c.reorderLevel());
    return items.save(i);
  }

  @Transactional
  public Item deactivate(UUID id) {
    Item i = require(id);
    i.deactivate();
    return items.save(i);
  }

  @Transactional(readOnly = true)
  public List<Item> list(String category) {
    String c = Texts.upper(category);
    return c == null ? items.findAllByOrderByNameAsc() : items.findByCategoryOrderByNameAsc(c);
  }

  @Transactional(readOnly = true)
  public Item require(UUID id) {
    return items.findById(id).orElseThrow(() -> ProblemException.notFound("item", id));
  }

  /**
   * The item a GRN line refers to: by spare id, else by item code, else a new item created from the
   * code (the PO named something the catalogue does not have yet). Null when the line has neither.
   */
  @Transactional
  public Item forReceipt(UUID spareId, String itemCode) {
    if (spareId != null) {
      Optional<Item> byId = items.findById(spareId);
      if (byId.isPresent()) {
        return byId.get();
      }
    }
    String code = Texts.code(itemCode);
    if (code == null) {
      return null;
    }
    return items.findByCode(code).orElseGet(() -> items.save(Item.create(code, code, "SPARE", null, null, 0)));
  }
}
