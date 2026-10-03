package in.societyos.utility.reference.infrastructure;

import in.societyos.utility.reference.domain.AssetRef;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetRefRepository extends JpaRepository<AssetRef, UUID> {}
